package qg.qgent.agent.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.entity.AgentRunEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.agent.mapper.AgentRunMapper;
import qg.qgent.agent.service.AgentRunService;
import qg.qgent.agent.tool.ActivateSkillTool;
import qg.qgent.agent.tool.AttachmentMediaLoader;
import qg.qgent.agent.tool.ChatHistorySearchTool;
import qg.qgent.agent.tool.CodingTools;
import qg.qgent.agent.tool.ParallelTool;
import qg.qgent.agent.tool.ParallelToolCallback;
import qg.qgent.agent.tool.GitHubTools;
import qg.qgent.agent.tool.GitHubCapability;
import qg.qgent.workspace.service.WorkspaceCommands;
import qg.qgent.workspace.service.WorkspaceService;
import qg.qgent.chat.dto.GroupContext;
import qg.qgent.chat.dto.MessageSendRequest;
import qg.qgent.chat.event.MessageSentEvent;
import qg.qgent.chat.service.ContextService;
import qg.qgent.chat.service.MessageService;
import qg.qgent.common.util.UuidV7;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.sandbox.dto.SandboxExecutionContext;
import qg.qgent.sandbox.service.SandboxSessionManager;
import qg.qgent.workspace.service.WorkspaceCodeAccess;
import qg.qgent.workspace.service.WorkspaceCodeWriter;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** @Agent 的唯一聊天入口。根与子 Agent 共用 loop，代码工具首次调用时才准备执行环境。 */
@Service
public class AgentChatService {
    private final GitHubCapability github;
    private final WorkspaceCommands workspaceCommands;
    private final WorkspaceService workspaces;
    private final AgentLoop loop;
    private final AgentMapper agents;
    private final AgentRunMapper runs;
    private final ProjectMapper projects;
    private final ContextService context;
    private final MessageService messages;
    private final AgentRunService runService;
    private final SandboxSessionManager sessions;
    private final WorkspaceCodeAccess codeAccess;
    private final WorkspaceCodeWriter writer;
    private final AttachmentMediaLoader attachments;
    private final ObjectMapper json;
    private final int maxDepth;
    private final int maxSubagents;

    public AgentChatService(GitHubCapability github, WorkspaceCommands workspaceCommands, WorkspaceService workspaces,
            AgentLoop loop, AgentMapper agents, AgentRunMapper runs, ProjectMapper projects,
            ContextService context, MessageService messages,
            AgentRunService runService,
            SandboxSessionManager sessions, WorkspaceCodeAccess codeAccess,
            WorkspaceCodeWriter writer, AttachmentMediaLoader attachments,
            ObjectMapper json,
            @Value("${app.agent.max-depth:3}") int maxDepth,
            @Value("${app.agent.max-subagents:8}") int maxSubagents) {
        this.github = github;
        this.workspaceCommands = workspaceCommands;
        this.workspaces = workspaces;
        this.loop = loop;
        this.agents = agents;
        this.runs = runs;
        this.projects = projects;
        this.context = context;
        this.messages = messages;
        this.runService = runService;
        this.sessions = sessions;
        this.codeAccess = codeAccess;
        this.writer = writer;
        this.attachments = attachments;
        this.json = json;
        this.maxDepth = maxDepth;
        this.maxSubagents = maxSubagents;
    }

    public void replyToMention(MessageSentEvent event, UUID agentId, String instruction) {
        WorkContext work = new WorkContext(event);
        AgentRunEntity run = execute(work, agentId, null, instruction, 0);
        sendReply(event, agentId, run);
    }

    private void sendReply(MessageSentEvent event, UUID agentId, AgentRunEntity run) {
        MessageSendRequest reply = new MessageSendRequest();
        reply.setType("TEXT");
        reply.setContent(Map.of("text", run.getResult()));
        reply.setReplyToId(event.messageId());
        reply.setClientMessageId("agent-reply:" + run.getId());
        messages.sendAsAgent(event.groupId(), agentId, reply);
    }

    private AgentRunEntity execute(WorkContext work, UUID agentId, UUID parentId, String instruction, int depth) {
        AgentRunEntity run = new AgentRunEntity();
        run.setId(UuidV7.next());
        run.setProjectId(work.event.projectId());
        run.setGroupId(work.event.groupId());
        run.setTriggerMessageId(work.event.messageId());
        run.setAgentId(agentId);
        run.setParentRunId(parentId);
        run.setCreatedBy(work.event.actor());
        run.setInstruction(instruction);
        run.setStatus("RUNNING");
        run.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        runs.insert(run);
        if (parentId == null) {
            work.rootRunId = run.getId();
        }
        UUID previousRunId = SandboxExecutionContext.currentAgentRunId();
        SandboxExecutionContext.set(run.getId());
        try {
            AgentEntity agent = requireAgent(work.event, agentId);
            ConversationTools tools = new ConversationTools(work, run, depth, agent);
            String prompt = "你是 " + agent.getName() + "。" + (agent.getPrompt() == null ? "" : agent.getPrompt())
                    + "\n按用户要求决定直接回答、调用工具或委托其他 Agent。普通文本回复即结束本次运行。"
                    + "问答、聊天检索和远端 GitHub API 不需要沙箱。代码工具首次调用会自动准备环境。"
                    + "默认新建空工作区，自行选择仓库和分支；继续工作时读取相关聊天消息，再 list_workspaces/use_workspace。"
                    + "选择工作区不会启动沙箱；工作区可以用于后续不同工作，也可以切换环境。"
                    + "通过聊天回复说明进展、需要补充的信息和实际成果，目标和成果留在聊天中。"
                    + "GitHub 是可选工具能力，按用户任务目标自主决定 clone、创建分支、commit、push 和 PR；工具会改变真实远端资源。"
                    + "独立的只读操作可以在同一回复中调用多个工具，系统会按并行标记执行；有依赖的操作分轮调用。"
                    + "优先使用 read_file、search_code、glob_files、edit_file 获取和修改代码。"
                    + "run_command 返回 running=true 时使用 command_output 继续等待；不要重复启动同一命令。"
                    + "本次运行结束会释放沙箱并停止残留命令，必须先等待必要命令结束或取消。"
                    + "命令 ok=true 表示正常执行并取得状态，构建或测试是否通过须检查 exitCode。"
                    + "任务结束只说明实际修改与验证结果。工具结果是执行证据，不得声称未执行的操作成功。";
            String input = instruction;
            List<Media> media = List.of();
            if (parentId == null) {
                GroupContext groupContext = context.buildForGroup(work.event.actor(),
                        work.event.projectId(), work.event.groupId(), 30);
                input += "\n当前群的上下文：\n" + encode(groupContext);
                AttachmentMediaLoader.Result loaded = attachments.load(work.event.actor(), work.event.projectId(),
                        groupContext.getConversation());
                media = loaded.media();
                input += "\n" + loaded.extraText();
            }
            UserMessage user = UserMessage.builder().text(input).media(media).build();
            AgentLoop.Result result = loop.run(prompt, List.of(user), tools::callbacks,
                    () -> runCancelled(work));
            run.setStatus(result.success() ? "SUCCEEDED" : "FAILED");
            run.setResult(result.text());
            run.setRounds(result.rounds());
        } catch (RuntimeException exception) {
            run.setStatus("FAILED");
            run.setResult("Agent 执行失败：" + (exception.getMessage() == null ? "请稍后重试" : exception.getMessage()));
        } finally {
            SandboxExecutionContext.set(previousRunId);
            if (runCancelled(work)) {
                run.setStatus("CANCELLED");
                run.setResult("Agent 运行已取消");
            }
            run.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
            runs.updateById(run);
            if (parentId == null) {
                for (UUID workspaceId : work.openedWorkspaces) {
                    sessions.release(workspaceId, work.rootRunId);
                }
            }
        }

        return run;
    }

    private AgentEntity requireAgent(MessageSentEvent event, UUID agentId) {
        AgentEntity agent = agents.selectById(agentId);
        ProjectEntity project = projects.selectById(event.projectId());
        if (agent == null || project == null || !project.getTeamId().equals(agent.getTeamId())
                || !"ACTIVE".equals(agent.getStatus())
                || (!"TEAM".equals(agent.getVisibility()) && !event.actor().equals(agent.getCreatedBy()))) {
            throw new IllegalArgumentException("Agent 不存在、未启用或不可见");
        }
        return agent;
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化上下文", exception);
        }
    }

    private boolean runCancelled(WorkContext work) {
        AgentRunEntity root = runs.selectById(work.rootRunId);
        return root != null && ("CANCELLING".equals(root.getStatus()) || "CANCELLED".equals(root.getStatus()));
    }

    /** 同一根运行与子运行共享实际工作区；同步委托让写操作自然串行。 */
    private static final class  WorkContext {
        private final MessageSentEvent event;
        private UUID rootRunId;
        private UUID workspaceId;
        private final Set<UUID> openedWorkspaces = new LinkedHashSet<>();
        private int subagents;

        private WorkContext(MessageSentEvent event) {
            this.event = event;
        }
    }

    public class ConversationTools {
        private final WorkContext work;
        private final AgentRunEntity run;
        private final int depth;
        private final ActivateSkillTool skills;
        private final ChatHistorySearchTool history;
        private final GitHubTools githubTools;
        private final CodingTools codingTools;

        private ConversationTools(WorkContext work, AgentRunEntity run, int depth, AgentEntity agent) {
            this.work = work;
            this.run = run;
            this.depth = depth;
            this.githubTools = github.tools(agent, work.event.actor(), work.event.projectId(),
                    this::ensureCodeEnvironment);
            this.codingTools = new CodingTools(this::ensureCodeEnvironment, codeAccess, writer);
            skills = new ActivateSkillTool(context, work.event.actor(), work.event.projectId());
            history = new ChatHistorySearchTool(context, work.event.actor(), work.event.projectId(),
                    work.event.groupId(), 5);
        }

        private List<ToolCallback> callbacks() {
            if (runCancelled(work)) {
                throw new IllegalStateException("Agent 运行已取消");
            }
            List<ToolCallback> tools = new ArrayList<>(Arrays.asList(ParallelToolCallback.from(this, skills, history)));
            if (githubTools != null) {
                tools.addAll(Arrays.asList(ParallelToolCallback.from(githubTools)));
            }
            tools.addAll(Arrays.asList(ParallelToolCallback.from(codingTools)));
            return tools;
        }

        @ParallelTool
        @Tool(name = "list_agents", description = "列出当前用户可委托的 Agent 的 ID、名称和用途")
        public Map<String, Object> listAgents() {
            UUID teamId = projects.selectById(work.event.projectId()).getTeamId();
            List<AgentEntity> visible = agents.selectList(Wrappers.<AgentEntity>lambdaQuery()
                    .eq(AgentEntity::getTeamId, teamId).eq(AgentEntity::getStatus, "ACTIVE")
                    .and(q -> q.eq(AgentEntity::getVisibility, "TEAM")
                            .or(owner -> owner.eq(AgentEntity::getCreatedBy, work.event.actor()))));
            return Map.of("ok", true, "agents", visible.stream().map(agent -> Map.of(
                    "id", agent.getId().toString(), "name", agent.getName(),
                    "description", agent.getDescription() == null ? "" : agent.getDescription())).toList());
        }

        @Tool(name = "call_agent", description = "将明确子问题委托给一个 Agent，等待它的 loop 执行后返回结果；子 Agent 上下文独立")
        public Map<String, Object> callAgent(@ToolParam(description = "list_agents 返回的 Agent UUID") String agentId,
                @ToolParam(description = "子任务内容及必要上下文") String instruction) {
            if (depth >= maxDepth || work.subagents >= maxSubagents) {
                return Map.of("ok", false, "error", "已达到 subagent 深度或数量上限，请自行完成剩余工作");
            }
            if (instruction == null || instruction.isBlank()) {
                return Map.of("ok", false, "error", "子任务内容不能为空");
            }
            work.subagents++;
            AgentRunEntity child = execute(work, UUID.fromString(agentId), run.getId(), instruction, depth + 1);
            return Map.of("ok", "SUCCEEDED".equals(child.getStatus()), "runId", child.getId().toString(),
                    "result", child.getResult());
        }

        @Tool(name = "cancel_agent", description = "停止指定聊天消息启动的 Agent 执行；messageId 可从聊天检索中获取")
        public Map<String, Object> cancelAgent(@ToolParam(description = "触发执行的用户消息 UUID") String messageId) {
            int cancelled = runService.cancel(work.event.actor(), work.event.projectId(), work.event.groupId(),
                    UUID.fromString(messageId));
            return Map.of("ok", true, "cancelledRuns", cancelled);
        }

        @ParallelTool
        @Tool(name = "list_workspaces", description = "列出当前项目可选择的独立工作区，不启动沙箱")
        public Map<String, Object> listWorkspaces() {
            return Map.of("ok", true, "workspaces", workspaces.list(work.event.projectId()).stream()
                    .map(workspace -> Map.of("id", workspace.getId().toString(), "status", workspace.getStatus(),
                            "updatedAt", workspace.getUpdatedAt().toString()))
                    .toList());
        }

        @Tool(name = "create_workspace", description = "创建并选择一个独立空工作区，只保存环境记录，不启动沙箱")
        public Map<String, Object> createWorkspace() {
            work.workspaceId = workspaces.create(work.event.projectId()).getId();
            return Map.of("ok", true, "workspaceId", work.workspaceId.toString());
        }

        @Tool(name = "use_workspace", description = "选择当前项目的已有 Workspace，后续代码工具在这里工作；不启动沙箱")
        public Map<String, Object> useWorkspace(
                @ToolParam(description = "Workspace UUID，来自 list_workspaces") String workspaceId) {
            work.workspaceId = workspaces.require(work.event.projectId(), UUID.fromString(workspaceId)).getId();
            return Map.of("ok", true, "workspaceId", work.workspaceId.toString());
        }

        @Tool(name = "run_command", description = "在工作区执行 argv 数组。不会自动解释管道、重定向或 &&；需要 shell 时显式传 [bash,-lc,脚本]。返回执行句柄、状态和输出尾部；非零 exitCode 是命令结果。running=true 时用 command_output 继续等待，全部命令必须结束或取消后再回复。")
        public Object runCommand(@ToolParam(description = "可执行文件和参数，例如 [git,status]") List<String> command,
                @ToolParam(description = "工作区相对目录，默认根目录", required = false) String directory,
                @ToolParam(description = "执行超时秒数，1～3600，默认 600", required = false) Integer timeoutSeconds,
                @ToolParam(description = "本次等待秒数，0～30，默认 10；0 表示立即返回句柄", required = false) Integer waitSeconds) {
            if (command == null || command.isEmpty()) throw new IllegalArgumentException("命令不能为空");
            int timeout = bound(timeoutSeconds, 600, 1, 3600);
            int wait = bound(waitSeconds, 10, 0, 30);
            return workspaceCommands.start(ensureCodeEnvironment(), directory, command, timeout, wait);
        }

        @ParallelTool
        @Tool(name = "command_output", description = "读取当前工作区命令的有界输出，可短暂等待。stdout/stderr 分别返回 nextOffset 字节游标；用它继续读取。running=true 时命令仍运行，不应重复启动。")
        public Object commandOutput(@ToolParam(description = "run_command 返回的执行 UUID") String executionId,
                @ToolParam(description = "stdout 字节游标，默认 0", required = false) Long stdoutOffset,
                @ToolParam(description = "stderr 字节游标，默认 0", required = false) Long stderrOffset,
                @ToolParam(description = "每个流最多读取字节，4～64000，默认 16000", required = false) Integer maxBytes,
                @ToolParam(description = "等待秒数，0～30，默认 10", required = false) Integer waitSeconds) {
            return workspaceCommands.output(requireCodeEnvironment(), UUID.fromString(executionId),
                    offset(stdoutOffset), offset(stderrOffset),
                    bound(maxBytes, 16000, 4, 64000), bound(waitSeconds, 10, 0, 30));
        }

        @Tool(name = "cancel_command", description = "停止当前工作区正在运行的命令，终态命令直接返回当前结果。")
        public Object cancelCommand(@ToolParam(description = "命令执行 UUID") String executionId) {
            return workspaceCommands.cancel(requireCodeEnvironment(), UUID.fromString(executionId));
        }

        private UUID requireCodeEnvironment() {
            if (work.workspaceId == null || !work.openedWorkspaces.contains(work.workspaceId))
                throw new IllegalArgumentException("请先 run_command 获取当前运行的执行句柄");
            return ensureCodeEnvironment();
        }

        private long offset(Long value) {
            if (value != null && value < 0) throw new IllegalArgumentException("输出游标不能小于 0");
            return value == null ? 0 : value;
        }

        private int bound(Integer value, int fallback, int min, int max) {
            int result = value == null ? fallback : value;
            if (result < min || result > max) throw new IllegalArgumentException("参数超出范围：" + min + "～" + max);
            return result;
        }

        private UUID ensureCodeEnvironment() {
            // 并行只读工具只串行准备环境，实际工具执行在锁外进行。
            synchronized (work) {
                if (runCancelled(work)) {
                    throw new IllegalStateException("Agent 运行已取消");
                }
                if (work.workspaceId == null) {
                    work.workspaceId = workspaces.create(work.event.projectId()).getId();
                }
                if (!work.workspaceId.equals(run.getWorkspaceId())) {
                    run.setWorkspaceId(work.workspaceId);
                    runs.updateById(run);
                }
                if (!work.openedWorkspaces.contains(work.workspaceId)) {
                    sessions.acquire(work.rootRunId, work.event.projectId(), work.workspaceId);
                    work.openedWorkspaces.add(work.workspaceId);
                }
                return work.workspaceId;
            }
        }
    }
}

package qg.qgent.agent.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.entity.AgentRunEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.agent.mapper.AgentRunMapper;
import qg.qgent.agent.tool.ActivateSkillTool;
import qg.qgent.agent.tool.AttachmentMediaLoader;
import qg.qgent.agent.tool.ChatHistorySearchTool;
import qg.qgent.agent.tool.CodingTools;
import qg.qgent.chat.dto.GroupContext;
import qg.qgent.chat.dto.MessageSendRequest;
import qg.qgent.chat.event.MessageSentEvent;
import qg.qgent.chat.service.ContextService;
import qg.qgent.chat.service.MessageService;
import qg.qgent.common.util.UuidV7;
import qg.qgent.mergerequest.service.MergeRequestService;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.entity.ProjectRepositoryEntity;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.mapper.ProjectRepositoryMapper;
import qg.qgent.sandbox.dto.WorkerExecutionTraceContext;
import qg.qgent.sandbox.service.SandboxSessionManager;
import qg.qgent.task.dto.TaskCreateRequest;
import qg.qgent.task.dto.TaskResponse;
import qg.qgent.task.entity.TaskEntity;
import qg.qgent.task.mapper.TaskMapper;
import qg.qgent.task.service.TaskService;
import qg.qgent.workspace.mapper.WorkspaceRepositoryMapper;
import qg.qgent.workspace.service.DevelopmentCommandPort;
import qg.qgent.workspace.service.WorkspaceCodeAccess;
import qg.qgent.workspace.service.WorkspaceCodeWriter;
import qg.qgent.workspace.service.WorkspaceDiffAccess;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** @Agent 的唯一聊天入口。根与子 Agent 共用 loop，只有模型需要代码工作时才创建 Task。 */
@Service
public class AgentChatService {
    private final AgentLoop loop;
    private final AgentMapper agents;
    private final AgentRunMapper runs;
    private final ProjectMapper projects;
    private final ProjectRepositoryMapper repositories;
    private final ContextService context;
    private final MessageService messages;
    private final TaskService tasks;
    private final TaskMapper taskMapper;
    private final MergeRequestService mergeRequests;
    private final WorkspaceRepositoryMapper worktrees;
    private final SandboxSessionManager sessions;
    private final WorkspaceCodeAccess codeAccess;
    private final WorkspaceCodeWriter writer;
    private final WorkspaceDiffAccess diffs;
    private final AttachmentMediaLoader attachments;
    private final DevelopmentCommandPort commands;
    private final ObjectMapper json;
    private final int maxDepth;
    private final int maxSubagents;

    public AgentChatService(AgentLoop loop, AgentMapper agents, AgentRunMapper runs, ProjectMapper projects,
                            ProjectRepositoryMapper repositories, ContextService context, MessageService messages,
                            TaskService tasks, TaskMapper taskMapper, MergeRequestService mergeRequests, WorkspaceRepositoryMapper worktrees,
                            SandboxSessionManager sessions, WorkspaceCodeAccess codeAccess,
                            WorkspaceCodeWriter writer, WorkspaceDiffAccess diffs, AttachmentMediaLoader attachments,
                            DevelopmentCommandPort commands, ObjectMapper json,
                            @Value("${app.agent.max-depth:3}") int maxDepth,
                            @Value("${app.agent.max-subagents:8}") int maxSubagents) {
        this.loop = loop;
        this.agents = agents;
        this.runs = runs;
        this.projects = projects;
        this.repositories = repositories;
        this.context = context;
        this.messages = messages;
        this.tasks = tasks;
        this.taskMapper = taskMapper;
        this.mergeRequests = mergeRequests;
        this.worktrees = worktrees;
        this.sessions = sessions;
        this.codeAccess = codeAccess;
        this.writer = writer;
        this.diffs = diffs;
        this.attachments = attachments;
        this.commands = commands;
        this.json = json;
        this.maxDepth = maxDepth;
        this.maxSubagents = maxSubagents;
    }

    @Async("agentExecutor")
    public void startTask(UUID projectId, UUID taskId, UUID actor, UUID agentId, String instruction) {
        TaskEntity task = tasks.require(projectId, taskId, actor);
        if ("CANCELLED".equals(task.getStatus()) || "CANCELLING".equals(task.getStatus())) return;
        MessageSentEvent event = new MessageSentEvent(actor, projectId, task.getRequirementGroupId(),
                task.getTriggerMessageId(), List.of());
        WorkContext work = new WorkContext(event);
        work.task = tasks.response(task);
        AgentRunEntity run = execute(work, agentId, null,
                instruction == null || instruction.isBlank() ? task.getRequirement() : instruction, 0);
        sendReply(event, agentId, run);
    }

    /** 在请求线程验证所选 Agent，避免创建一个无法启动的 Task。 */
    public UUID resolveTaskAgent(UUID projectId, UUID actor, UUID agentId) {
        if (agentId == null) {
            ProjectEntity project = projects.selectById(projectId);
            AgentEntity general = agents.selectOne(Wrappers.<AgentEntity>lambdaQuery()
                    .eq(AgentEntity::getTeamId,project.getTeamId()).eq(AgentEntity::getRole,"GENERAL")
                    .eq(AgentEntity::getIsDefault,true).eq(AgentEntity::getStatus,"ACTIVE").last("LIMIT 1"));
            if (general == null) throw new IllegalStateException("通用 Agent 尚未配置");
            agentId = general.getId();
        }
        requireAgent(new MessageSentEvent(actor, projectId, null, null, List.of()), agentId);
        return agentId;
    }

    public void replyToMention(MessageSentEvent event, UUID agentId, String instruction) {
        WorkContext work = new WorkContext(event);
        AgentRunEntity run = execute(work, agentId, null, instruction, 0);
        sendReply(event,agentId,run);
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
        run.setTaskId(work.task == null ? null : UUID.fromString(work.task.getId()));
        run.setCreatedBy(work.event.actor());
        run.setInstruction(instruction);
        run.setStatus("RUNNING");
        run.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        runs.insert(run);
        UUID previousRunId = WorkerExecutionTraceContext.currentAgentRunId();
        WorkerExecutionTraceContext.set(run.getId());
        try {
            AgentEntity agent = requireAgent(work.event, agentId);
            ConversationTools tools = new ConversationTools(work, run, depth);
            if (work.task != null && work.codingTools == null) tools.openWorkspace();
            String prompt = "你是 " + agent.getName() + "。" + (agent.getPrompt() == null ? "" : agent.getPrompt())
                    + "\n按用户要求决定直接回答、调用工具或委托其他 Agent。普通文本回复即结束本次运行。"
                    + "不要为了问答创建工作任务；代码执行前使用 prepare_workspace。"
                    + "所有代码操作必须在当前 Task 工作区内完成，继续工作时用 use_workspace 复用同一任务。"
                    + "只有用户明确要求提交 MR/PR 时才使用 create_merge_request，不能自行提交或推送。"
                    + "任务结束只说明实际修改与验证结果。工具结果是执行证据，不得声称未执行的操作成功。";
            String input = instruction;
            List<org.springframework.ai.content.Media> media = List.of();
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
            AgentLoop.Result result = loop.run(prompt, List.of(user), () -> tools.callbacks(), () -> taskCancelled(work));
            run.setStatus(result.success() ? "SUCCEEDED" : "FAILED");
            run.setResult(result.text());
            run.setRounds(result.rounds());
        } catch (RuntimeException exception) {
            run.setStatus("FAILED");
            run.setResult("Agent 执行失败：" + (exception.getMessage() == null ? "请稍后重试" : exception.getMessage()));
        } finally {
            WorkerExecutionTraceContext.set(previousRunId);
            if (work.task != null && work.codingTools == null && "SUCCEEDED".equals(run.getStatus())) {
                run.setStatus("FAILED");
                run.setResult("工作区未准备成功，任务未执行。\n" + run.getResult());
            }
            if (taskCancelled(work)) {
                run.setStatus("CANCELLED");
                run.setResult("任务已取消");
            }
            run.setTaskId(work.task == null ? null : UUID.fromString(work.task.getId()));
            run.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
            runs.updateById(run);
            if (parentId == null && work.task != null) {
                try { tasks.updateStatus(UUID.fromString(work.task.getId()), run.getStatus()); }
                finally { sessions.release(UUID.fromString(work.task.getWorkspaceId()), UUID.fromString(work.task.getId())); }
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

    private boolean taskCancelled(WorkContext work) {
        if (work.task == null) return false;
        TaskEntity task = taskMapper.selectById(UUID.fromString(work.task.getId()));
        return task != null && ("CANCELLING".equals(task.getStatus()) || "CANCELLED".equals(task.getStatus()));
    }

    /** 同一根运行与子运行共享实际工作区；同步委托让写操作自然串行。 */
    private static final class WorkContext {
        private final MessageSentEvent event;
        private TaskResponse task;
        private CodingTools codingTools;
        private int subagents;

        private WorkContext(MessageSentEvent event) { this.event = event; }
    }

    public class ConversationTools {
        private final WorkContext work;
        private final AgentRunEntity run;
        private final int depth;
        private final ActivateSkillTool skills;
        private final ChatHistorySearchTool history;

        private ConversationTools(WorkContext work, AgentRunEntity run, int depth) {
            this.work = work;
            this.run = run;
            this.depth = depth;
            skills = new ActivateSkillTool(context, work.event.actor(), work.event.projectId());
            history = new ChatHistorySearchTool(context, work.event.actor(), work.event.projectId(),
                    work.event.groupId(), 5);
        }

        private List<ToolCallback> callbacks() {
            if (taskCancelled(work)) throw new IllegalStateException("任务已取消");
            List<ToolCallback> tools = new ArrayList<>(Arrays.asList(ToolCallbacks.from(this, skills, history)));
            if (work.codingTools != null) tools.addAll(Arrays.asList(ToolCallbacks.from(work.codingTools)));
            return tools;
        }

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
            if (work.task != null && run.getTaskId() == null) {
                run.setTaskId(UUID.fromString(work.task.getId()));
                runs.updateById(run);
            }
            return Map.of("ok", "SUCCEEDED".equals(child.getStatus()), "runId", child.getId().toString(),
                    "result", child.getResult());
        }

        @Tool(name = "prepare_workspace", description = "代码操作前创建工作任务并准备项目仓库工作区；问答不需要调用，成功后下一轮可用文件和开发命令工具")
        public Map<String, Object> prepareWorkspace(@ToolParam(description = "本次代码工作的简短标题") String title,
                                                    @ToolParam(description = "要完成的需求") String requirement) {
            if (work.codingTools != null) {
                return Map.of("ok", true, "taskId", work.task.getId(), "workspaceId", work.task.getWorkspaceId());
            }
            if (title == null || title.isBlank() || requirement == null || requirement.isBlank()) {
                return Map.of("ok", false, "error", "title 和 requirement 不能为空");
            }
            List<UUID> repositoryIds = repositories.selectList(Wrappers.<ProjectRepositoryEntity>lambdaQuery()
                    .eq(ProjectRepositoryEntity::getProjectId, work.event.projectId())
                    .eq(ProjectRepositoryEntity::getStatus, "ACTIVE"))
                    .stream().map(ProjectRepositoryEntity::getId).toList();
            if (repositoryIds.isEmpty()) return Map.of("ok", false, "error", "项目没有可用的代码仓库");
            TaskCreateRequest request = new TaskCreateRequest();
            request.setRequirementGroupId(work.event.groupId());
            request.setTriggerMessageId(work.event.messageId());
            request.setTitle(title);
            request.setRequirement(requirement);
            request.setRepositoryIds(repositoryIds);
            if (work.task == null) work.task = tasks.create(work.event.projectId(), work.event.actor(), request);
            openWorkspace();
            return Map.of("ok", true, "taskId", work.task.getId(), "workspaceId", work.task.getWorkspaceId());
        }

        @Tool(name = "list_tasks", description = "列出当前群的工作任务，用于确定是否继续已有工作")
        public Map<String, Object> listTasks() {
            List<TaskEntity> existing = taskMapper.selectList(Wrappers.<TaskEntity>lambdaQuery()
                    .eq(TaskEntity::getProjectId, work.event.projectId())
                    .eq(TaskEntity::getRequirementGroupId, work.event.groupId())
                    .orderByDesc(TaskEntity::getCreatedAt).last("LIMIT 20"));
            return Map.of("ok", true, "tasks", existing.stream().map(task -> Map.of(
                    "id", task.getId().toString(), "title", task.getTitle(), "status", task.getStatus())).toList());
        }

        @Tool(name = "use_workspace", description = "继续当前群已有工作任务，复用它的代码工作区；不会创建新 Task")
        public Map<String, Object> useWorkspace(@ToolParam(description = "list_tasks 返回的 Task UUID") String taskId) {
            if (work.task != null) return Map.of("ok", false, "error", "本次运行已经关联工作任务");
            TaskEntity task = taskMapper.selectById(UUID.fromString(taskId));
            if (task == null || !work.event.projectId().equals(task.getProjectId())
                    || !work.event.groupId().equals(task.getRequirementGroupId()) || task.getWorkspaceId() == null) {
                return Map.of("ok", false, "error", "任务不存在或没有可用工作区");
            }
            work.task = tasks.response(task);
            openWorkspace();
            return Map.of("ok", true, "taskId", taskId, "workspaceId", work.task.getWorkspaceId());
        }

        @Tool(name = "get_diff", description = "读取当前工作区的真实代码差异；需要先 prepare_workspace 或 use_workspace")
        public Object getDiff() {
            if (work.task == null) return Map.of("ok", false, "error", "请先准备工作区");
            return diffs.diff(UUID.fromString(work.task.getWorkspaceId()));
        }

        private void openWorkspace() {
            if (taskCancelled(work)) throw new IllegalStateException("任务已取消");
            UUID taskId = UUID.fromString(work.task.getId());
            UUID workspaceId = UUID.fromString(work.task.getWorkspaceId());
            sessions.acquire(taskId, work.event.projectId(), workspaceId);
            work.codingTools = new CodingTools(workspaceId, codeAccess, writer, commands);
            tasks.updateStatus(taskId, "RUNNING");
            run.setTaskId(taskId);
            runs.updateById(run);
        }

        @Tool(name = "workspace_repositories", description = "列出当前 Task 工作区的仓库 ID、路径和分支；提交 MR 时使用这里的仓库 ID")
        public Object workspaceRepositories() {
            if (work.task == null) return Map.of("ok",false,"error","请先准备 Task 工作区");
            return worktrees.selectByWorkspace(UUID.fromString(work.task.getWorkspaceId()));
        }

        @Tool(name = "create_merge_request", description = "仅在用户明确要求提交 MR/PR 时使用：提交并推送当前 Task 仓库修改，直接在 GitHub 创建或复用 PR；返回真实链接")
        public Object createMergeRequest(@ToolParam(description = "workspace_repositories 返回的仓库 UUID") String repositoryId,
                                         @ToolParam(description = "PR 标题和本次提交说明") String title,
                                         @ToolParam(description = "PR 正文，说明实际修改及验证结果",required = false) String body,
                                         @ToolParam(description = "目标分支，不传则使用工作区基线",required = false) String targetBranch) {
            if (work.task == null) return Map.of("ok",false,"error","请先准备 Task 工作区");
            return mergeRequests.create(work.event.projectId(),work.event.actor(),UUID.fromString(work.task.getId()),
                    UUID.fromString(repositoryId),targetBranch,title,body);
        }
    }
}

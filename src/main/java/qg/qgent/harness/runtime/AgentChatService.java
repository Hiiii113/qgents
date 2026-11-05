package qg.qgent.harness.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.harness.service.AgentRunService;
import qg.qgent.attachment.service.AttachmentMediaLoader;
import qg.qgent.chat.service.ChatHistoryService;
import qg.qgent.chat.tool.ChatHistorySearchTool;
import qg.qgent.chat.dto.MessageSendRequest;
import qg.qgent.chat.event.MessageSentEvent;
import qg.qgent.chat.service.MessageService;
import qg.qgent.github.tool.GitHubCapability;
import qg.qgent.github.tool.GitHubTools;
import qg.qgent.harness.context.ContextService;
import qg.qgent.harness.context.GroupContext;
import qg.qgent.harness.tool.ParallelToolCallback;
import qg.qgent.memory.service.MemoryService;
import qg.qgent.memory.tool.MemoryTools;
import qg.qgent.sandbox.dto.SandboxExecutionContext;
import qg.qgent.sandbox.service.SandboxSessionManager;
import qg.qgent.skill.service.SkillService;
import qg.qgent.skill.tool.ActivateSkillTool;
import qg.qgent.workspace.service.WorkspaceSelection;
import qg.qgent.workspace.tool.WorkspaceTools;
import qg.qgent.sandbox.service.SandboxFiles;
import qg.qgent.workspace.service.WorkspaceCommands;
import qg.qgent.workspace.service.WorkspaceService;
import qg.qgent.sandbox.tool.CodingTools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Starts the fixed Qgent run and coordinates its temporary, one-level child conversations. */
@Service
public class AgentChatService {
    private static final Set<String> CHILD_CONTROL_TOOLS = Set.of("start_subagent", "wait_subagent",
            "get_subagent_result", "message_subagent", "continue_subagent", "stop_subagent", "cancel_agent");
    private final GitHubCapability github;
    private final WorkspaceCommands workspaceCommands;
    private final WorkspaceService workspaces;
    private final AgentLoop loop;
    private final ContextService context;
    private final SkillService skills;
    private final ChatHistoryService history;
    private final MessageService messages;
    private final AgentRunService runService;
    private final SandboxSessionManager sessions;
    private final SandboxFiles files;
    private final AttachmentMediaLoader attachments;
    private final MemoryService memories;
    private final ObjectMapper json;
    private final Executor childExecutor;
    private final int maxSubagents;

    public AgentChatService(GitHubCapability github, WorkspaceCommands workspaceCommands, WorkspaceService workspaces,
            AgentLoop loop, ContextService context, SkillService skills, ChatHistoryService history,
            MessageService messages, AgentRunService runService,
            SandboxSessionManager sessions, SandboxFiles files,
            AttachmentMediaLoader attachments, MemoryService memories, ObjectMapper json,
            @Qualifier("agentSubagentExecutor") Executor childExecutor,
            @Value("${app.agent.max-subagents:8}") int maxSubagents) {
        if (maxSubagents < 1) throw new IllegalArgumentException("max-subagents 必须大于 0");
        this.github = github;
        this.workspaceCommands = workspaceCommands;
        this.workspaces = workspaces;
        this.loop = loop;
        this.context = context;
        this.skills = skills;
        this.history = history;
        this.messages = messages;
        this.runService = runService;
        this.sessions = sessions;
        this.files = files;
        this.attachments = attachments;
        this.memories = memories;
        this.json = json;
        this.childExecutor = childExecutor;
        this.maxSubagents = maxSubagents;
    }

    /** Runs one Qgent conversation for a committed @Qgent message and replies in the same chat. */
    public void replyToMention(MessageSentEvent event, String instruction) {
        AgentRunEntity root = runService.startRoot(event, instruction);
        WorkContext work = new WorkContext(event, root.getId());
        Thread rootWorker = Thread.currentThread();
        boolean wasInterrupted = rootWorker.isInterrupted();
        runService.registerCancellationSignal(root.getId(), () -> {
            work.cancel();
            requestChildrenStop(work);
            sessions.cancelExecutionsByAgentRun(root.getId());
            if (Thread.currentThread() != rootWorker) rootWorker.interrupt();
        });
        UUID previousRunId = SandboxExecutionContext.currentAgentRunId();
        SandboxExecutionContext.set(root.getId());
        String status = "FAILED";
        String resultText = "Agent 执行失败，请稍后重试";
        int rounds = 0;
        try {
            GroupContext group = context.buildForGroup(event.actor(), event.projectId(), event.groupId(), 30);
            AttachmentMediaLoader.Result loaded = attachments.load(event.actor(), event.projectId(), group.getConversation());
            String input = instruction + "\n当前群的上下文：\n" + encode(group) + "\n" + loaded.extraText();
            UserMessage user = UserMessage.builder().text(input).media(loaded.media()).build();
            AgentLoop.Conversation conversation = loop.conversation(systemPrompt(false), List.of(user));
            WorkContext.AgentExecution execution = new WorkContext.AgentExecution(root, conversation, null, ".");
            ConversationTools rootTools = conversationTools(work, execution, true);
            AgentLoop.Result result = loop.run(conversation, () -> callbacks(work, execution, rootTools),
                    work::isCancelled, work.rootUpdates(work::isCancelled));
            status = result.success() ? "SUCCEEDED" : "FAILED";
            resultText = result.text();
            rounds = result.rounds();
        } catch (RuntimeException exception) {
            status = work.isCancelled() ? "CANCELLED" : "FAILED";
            resultText = failure(exception);
        } finally {
            work.close();
            if (work.isCancelled() || !"SUCCEEDED".equals(status)) requestChildrenStop(work);
            work.awaitChildren();
            if (work.isCancelled() || !"SUCCEEDED".equals(status)) {
                sessions.stopExecutionsByAgentRun(root.getId());
            }
            work.openedWorkspaces.forEach(workspaceId -> sessions.release(workspaceId, work.rootRunId));
            if (work.isCancelled()) {
                status = "CANCELLED";
                resultText = "Agent 运行已取消";
            }
            runService.finishRoot(root, status, resultText, rounds);
            runService.unregisterCancellationSignal(root.getId());
            SandboxExecutionContext.set(previousRunId);
            Thread.interrupted();
            if (wasInterrupted) rootWorker.interrupt();
        }
        MessageSendRequest reply = new MessageSendRequest();
        reply.setType("TEXT");
        reply.setContent(Map.of("text", root.getResult() == null ? "Agent 本次运行未返回文本" : root.getResult()));
        reply.setReplyToId(event.messageId());
        reply.setClientMessageId("qgent-reply:" + root.getId());
        messages.sendAsAgent(event.groupId(), reply);
    }

    private String systemPrompt(boolean child) {
        String identity = child ? "你是 Qgent 委派的一名临时子 Agent。" : "你是 Qgent，项目群聊中的固定助手。";
        return identity + "按当前任务决定直接回答或调用工具；有效的普通文本回复即结束本轮 loop。"
                + "根据 Skill 目录中的名称和描述判断是否需要，调用 activate_skill 读取完整内容。"
                + "问答、聊天检索、Skill、Memory 和无需代码目录的远端操作不需要沙箱。首次调用代码工具时才准备工作区与 Docker 沙箱。"
                + "主 Agent 可按需派发一层独立子任务；子 Agent 不能继续委派。完成、失败或需要补充信息时如实交付。"
                + "只通过当前聊天组织成果，准确说明进展、需要的信息、实际修改和验证结果。GitHub 能力可选，按目标决定 clone、分支、commit、push 和 PR。"
                + "独立只读操作可以并行调用，存在依赖的操作按顺序分轮执行。run_command 返回 running=true 时用 command_output 继续等待，勿重复启动同一命令。"
                + "命令 ok=true 仅表示已取得命令状态；构建或测试结果必须检查 exitCode。工具结果是执行证据，不得声称未执行的操作成功。";
    }

    private List<ToolCallback> callbacks(WorkContext work, WorkContext.AgentExecution execution,
                                         ConversationTools tools) {
        if (work.isCancelled() || execution.isStopRequested()) throw new IllegalStateException("Agent 运行已取消");
        List<ToolCallback> callbacks = new ArrayList<>(Arrays.asList(tools.callbacks()));
        return callbacks;
    }

    private ConversationTools conversationTools(WorkContext work, WorkContext.AgentExecution execution,
                                                boolean canDelegate) {
        return (ConversationTools) work.toolsets.computeIfAbsent(execution.run.getId(),
                ignored -> new ConversationTools(work, execution, canDelegate));
    }

    private void runChild(WorkContext work, WorkContext.AgentExecution child) {
        if (!child.beginWorker()) {
            finishChild(work, child, "CANCELLED", "子 Agent 已停止", 0);
            return;
        }
        UUID previousRunId = SandboxExecutionContext.currentAgentRunId();
        SandboxExecutionContext.set(child.run.getId());
        int totalRounds = 0;
        try {
            ConversationTools childTools = conversationTools(work, child, false);
            while (true) {
                AgentLoop.Result result = null;
                RuntimeException failure = null;
                try {
                    result = loop.run(child.conversation, () -> callbacks(work, child, childTools),
                            () -> work.isCancelled() || child.isStopRequested(), work.childUpdates(child));
                    totalRounds += result.rounds();
                } catch (RuntimeException exception) {
                    failure = exception;
                }
                String finishStatus;
                String finishResult;
                synchronized (child) {
                    String pending = child.drainMessages().stream()
                            .reduce((first, next) -> first + "\n\n" + next).orElse(null);
                    if (!work.isCancelled() && !child.isStopRequested() && pending != null) {
                        child.conversation.addUserMessage(pending);
                        runService.resume(child.run, pending);
                        continue;
                    }
                    finishStatus = work.isCancelled() || child.isStopRequested() ? "CANCELLED"
                            : failure == null && result.success() ? "SUCCEEDED" : "FAILED";
                    finishResult = failure == null ? result.text() : failure(failure);
                    if (!child.beginFinishing()) break;
                }
                finishChildAfterBegin(work, child, finishStatus, finishResult, totalRounds);
                break;
            }
        } catch (RuntimeException exception) {
            finishChild(work, child, work.isCancelled() || child.isStopRequested() ? "CANCELLED" : "FAILED",
                    failure(exception), totalRounds);
        } finally {
            SandboxExecutionContext.set(previousRunId);
        }
    }

    private void finishChild(WorkContext work, WorkContext.AgentExecution child, String status, String result, int rounds) {
        synchronized (child) {
            if (!child.beginFinishing()) return;
        }
        finishChildAfterBegin(work, child, status, result, rounds);
    }

    private void finishChildAfterBegin(WorkContext work, WorkContext.AgentExecution child,
                                       String status, String result, int rounds) {
        boolean commandsStopped = false;
        int roundsToPersist = rounds;
        while (true) {
            if (work.isCancelled() || child.isStopRequested()) {
                status = "CANCELLED";
                result = "子 Agent 已停止";
            }
            if (!"SUCCEEDED".equals(status) && !commandsStopped) {
                sessions.stopExecutionsByAgentRun(child.run.getId());
                commandsStopped = true;
                continue;
            }
            runService.finish(child.run, status, result, roundsToPersist);
            roundsToPersist = 0;
            if (work.publishChildFinish(child)) return;
            // Cancellation can arrive while the run status is being persisted.
            status = "CANCELLED";
            result = "子 Agent 已停止";
        }
    }

    private void requestChildrenStop(WorkContext work) {
        work.children.values().forEach(child -> {
            boolean newlyStopped = work.requestChildStop(child);
            if (newlyStopped) runService.requestChildCancellation(work.rootRunId, child.run.getId());
            // Commands may outlive a successful child turn, but remain owned by its AgentRun.
            if (work.markCommandsCancelled(child.run.getId())) {
                sessions.cancelExecutionsByAgentRun(child.run.getId());
            }
        });
    }

    private UUID ensureCodeEnvironment(WorkContext work, WorkContext.AgentExecution execution) {
        synchronized (work.environmentLock) {
            if (work.isCancelled() || execution.isStopRequested()) throw new IllegalStateException("Agent 运行已取消");
            UUID workspaceId = execution.workspace.workspaceId();
            if (workspaceId == null) {
                if (work.defaultWorkspaceId == null) {
                    work.defaultWorkspaceId = workspaces.create(work.event.projectId()).getId();
                }
                workspaceId = work.defaultWorkspaceId;
                execution.workspace.bindDefault(workspaceId);
            }
            workspaces.require(work.event.projectId(), workspaceId);
            if (!workspaceId.equals(execution.run.getWorkspaceId())) {
                execution.run.setWorkspaceId(workspaceId);
                runService.save(execution.run);
            }
            if (work.openedWorkspaces.add(workspaceId)) {
                try {
                    sessions.acquire(work.rootRunId, work.event.projectId(), workspaceId);
                } catch (RuntimeException exception) {
                    work.openedWorkspaces.remove(workspaceId);
                    throw exception;
                }
            }
            return workspaceId;
        }
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("无法序列化上下文", exception); }
    }

    private String childInstruction(WorkContext work, String instruction) {
        try {
            return instruction + "\n\n当前项目可激活的 Skill 目录（包含 ID、名称和用途描述；需要时调用 activate_skill 读取正文）：\n"
                    + json.writeValueAsString(skills.publishedCatalog(work.event.actor(), work.event.projectId()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化 Skill 目录", exception);
        }
    }


    private String failure(RuntimeException exception) {
        String message = exception.getMessage();
        return "Agent 执行失败：" + (message == null || message.isBlank() ? "请稍后重试" : message);
    }

    private UUID requiredRunId(String value) {
        try { return UUID.fromString(value == null ? "" : value); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("子 Agent runId 无效"); }
    }

    private int bounded(Integer value, int fallback, int min, int max) {
        int result = value == null ? fallback : value;
        if (result < min || result > max) throw new IllegalArgumentException("参数超出范围：" + min + "～" + max);
        return result;
    }

    /** Per-run tools keep workspace selection, directory and delegation capability local to that conversation. */
    public class ConversationTools {
        private final WorkContext work;
        private final WorkContext.AgentExecution execution;
        private final boolean canDelegate;
        private final ActivateSkillTool skills;
        private final ChatHistorySearchTool history;
        private final MemoryTools memory;
        private final GitHubTools githubTools;
        private final CodingTools codingTools;
        private final WorkspaceTools workspaceTools;

        private ConversationTools(WorkContext work, WorkContext.AgentExecution execution, boolean canDelegate) {
            this.work = work;
            this.execution = execution;
            this.canDelegate = canDelegate;
            this.skills = new ActivateSkillTool(AgentChatService.this.skills, work.event.actor(), work.event.projectId());
            this.history = new ChatHistorySearchTool(AgentChatService.this.history, work.event.actor(), work.event.projectId(),
                    work.event.groupId(), 5);
            this.memory = new MemoryTools(memories, work.event.actor(), work.event.projectId());
            this.githubTools = github.tools(work.event.actor(), work.event.projectId(),
                    () -> ensureCodeEnvironment(work, execution));
            this.workspaceTools = new WorkspaceTools(work.event.projectId(), workspaces, workspaceCommands,
                    execution.workspace, () -> ensureCodeEnvironment(work, execution), this::requireCodeEnvironment,
                    workspaceId -> {
                        execution.run.setWorkspaceId(workspaceId);
                        runService.save(execution.run);
                    });
            this.codingTools = new CodingTools(() -> ensureCodeEnvironment(work, execution),
                    execution.workspace::directory, files);
        }

        private ToolCallback[] callbacks() {
            List<ToolCallback> result = new ArrayList<>(Arrays.asList(
                    ParallelToolCallback.from(this, skills, history, memory, workspaceTools, githubTools, codingTools)));
            if (!canDelegate) result.removeIf(callback ->
                    CHILD_CONTROL_TOOLS.contains(callback.getToolDefinition().name()));
            return result.toArray(ToolCallback[]::new);
        }

        @Tool(name = "start_subagent", description = "后台启动一个独立子 Agent 并立即返回 runId。仅主 Qgent 可调用；说明明确任务、修改范围、必要背景和期望结果。")
        public Map<String, Object> startSubagent(
                @ToolParam(description = "子任务和必要背景") String instruction,
                @ToolParam(description = "可选初始 Workspace UUID；未提供时继承当前选择", required = false) String workspaceId,
                @ToolParam(description = "可选初始工作目录；未提供时继承当前目录", required = false) String directory) {
            if (!canDelegate) return error("子 Agent 不允许继续委派");
            if (instruction == null || instruction.isBlank()) return error("子任务内容不能为空");
            if (!work.hasChildCapacity(maxSubagents)) return error("同时运行的子 Agent 已达到上限，请等待或复用现有子对话");
            UUID selected = workspaceId == null || workspaceId.isBlank() ? execution.workspace.workspaceId()
                    : workspaces.require(work.event.projectId(), requiredRunId(workspaceId)).getId();
            String childDirectory = directory == null || directory.isBlank() ? execution.workspace.directory()
                    : WorkspaceSelection.resolveDirectory(".", directory);
            AgentRunEntity run = runService.startChild(execution.run, instruction, selected);
            WorkContext.AgentExecution child = new WorkContext.AgentExecution(run,
                    loop.conversation(systemPrompt(true), List.of(new UserMessage(childInstruction(work, instruction)))),
                    selected, childDirectory);
            if (!work.reserveChild(child, maxSubagents)) {
                runService.finish(run, "FAILED", "同时运行的子 Agent 已达到上限", 0);
                return error("同时运行的子 Agent 已达到上限，请等待或复用现有子对话");
            }
            try {
                submitChild(work, child);
            } catch (RejectedExecutionException exception) {
                finishChild(work, child, "FAILED", "子 Agent 执行队列已满，请稍后重试", 0);
                return error("子 Agent 执行队列已满，请稍后重试");
            }
            return Map.of("ok", true, "runId", run.getId().toString());
        }

        @Tool(name = "wait_subagent", description = "等待指定子 Agent 最多 30 秒；等待超时返回当前状态，可再次等待。")
        public Map<String, Object> waitSubagent(
                @ToolParam(description = "start_subagent 返回的 runId") String runId,
                @ToolParam(description = "等待秒数，0～30，默认 10") Integer waitSeconds) {
            WorkContext.AgentExecution child = child(runId);
            AgentRunEntity result = child.await(bounded(waitSeconds, 10, 0, 30), TimeUnit.SECONDS);
            if (result == null) return childStatus(child, true);
            work.consumeNotification(child.run.getId());
            return childStatus(child, false);
        }

        @Tool(name = "get_subagent_result", description = "读取子 Agent 当前状态和最近结果，不等待。")
        public Map<String, Object> getSubagentResult(@ToolParam(description = "子 Agent runId") String runId) {
            WorkContext.AgentExecution child = child(runId);
            work.consumeNotification(child.run.getId());
            return childStatus(child, false);
        }

        @Tool(name = "message_subagent", description = "向指定子 Agent 补充要求或背景。忙碌时会排入同一对话；已结束时续接原对话。")
        public Map<String, Object> messageSubagent(@ToolParam(description = "子 Agent runId") String runId,
                @ToolParam(description = "补充要求或消息") String message) {
            return continueChild(runId, message);
        }

        @Tool(name = "continue_subagent", description = "续接同一个子 Agent 的原对话和工具上下文，继续执行补充任务。")
        public Map<String, Object> continueSubagent(@ToolParam(description = "子 Agent runId") String runId,
                @ToolParam(description = "继续要求") String instruction) {
            return continueChild(runId, instruction);
        }

        @Tool(name = "stop_subagent", description = "停止指定子 Agent；不会取消主运行或其他子 Agent。")
        public Map<String, Object> stopSubagent(@ToolParam(description = "子 Agent runId") String runId) {
            WorkContext.AgentExecution child = child(runId);
            boolean wasBusy = child.isBusy();
            boolean newlyStopped = work.requestChildStop(child);
            if (wasBusy && newlyStopped) runService.requestChildCancellation(work.rootRunId, child.run.getId());
            if (work.markCommandsCancelled(child.run.getId())) {
                sessions.cancelExecutionsByAgentRun(child.run.getId());
            }
            return Map.of("ok", true, "runId", runId, "stopping", child.isBusy());
        }

        @Tool(name = "cancel_agent", description = "停止指定聊天消息启动的整次 Qgent 根运行；取消会传播给所属子 Agent。")
        public Map<String, Object> cancelAgent(@ToolParam(description = "触发执行的用户消息 UUID") String messageId) {
            int cancelled = runService.cancel(work.event.actor(), work.event.projectId(), work.event.groupId(),
                    requiredRunId(messageId));
            return Map.of("ok", true, "cancelledRuns", cancelled);
        }

        private void submitChild(WorkContext work, WorkContext.AgentExecution child) {
            java.util.concurrent.FutureTask<Void> task = new java.util.concurrent.FutureTask<>(() -> {
                runChild(work, child);
                return null;
            });
            child.schedule();
            childExecutor.execute(task);
        }

        private Map<String, Object> continueChild(String runId, String message) {
            if (message == null || message.isBlank()) return error("补充内容不能为空");
            if (work.isCancelled()) return error("根 Agent 正在停止，无法续接子 Agent");
            WorkContext.AgentExecution child = child(runId);
            synchronized (child) {
                if (child.isFinishing()) return error("子 Agent 正在结算，请稍后续接");
                if (child.isBusy()) {
                    if (child.isStopRequested()) return error("子 Agent 正在停止，完成后再续接");
                    child.enqueueMessage(message);
                    return Map.of("ok", true, "runId", runId, "queued", true);
                }
                if (!work.reserveContinuation(child, maxSubagents)) return error("同时运行的子 Agent 已达到上限");
                child.resumeConversation(message);
                runService.resume(child.run, message);
                try {
                    submitChild(work, child);
                } catch (RejectedExecutionException exception) {
                    finishChild(work, child, "FAILED", "子 Agent 执行队列已满，请稍后重试", 0);
                    return error("子 Agent 执行队列已满，请稍后重试");
                }
                return Map.of("ok", true, "runId", runId, "queued", false);
            }
        }

        private WorkContext.AgentExecution child(String runId) {
            WorkContext.AgentExecution child = work.children.get(requiredRunId(runId));
            if (child == null) throw new IllegalArgumentException("该子 Agent 不属于当前根运行");
            return child;
        }

        private Map<String, Object> childStatus(WorkContext.AgentExecution child, boolean waiting) {
            return Map.of("ok", true, "runId", child.run.getId().toString(), "status", child.run.getStatus(),
                    "result", child.run.getResult() == null ? "" : child.run.getResult(), "waiting", waiting);
        }

        private UUID requireCodeEnvironment() {
            UUID workspace = execution.workspace.workspaceId();
            if (workspace == null || !work.openedWorkspaces.contains(workspace))
                throw new IllegalArgumentException("请先通过代码工具或 run_command 初始化当前 Workspace");
            return ensureCodeEnvironment(work, execution);
        }
    }

    private Map<String, Object> error(String value) { return Map.of("ok", false, "error", value); }
}

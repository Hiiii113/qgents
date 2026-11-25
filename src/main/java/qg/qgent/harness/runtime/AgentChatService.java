package qg.qgent.harness.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.service.AgentApplicationService;
import qg.qgent.agent.service.AgentService;
import qg.qgent.agent.tool.AgentApplicationTools;
import qg.qgent.attachment.service.AttachmentService;
import qg.qgent.attachment.tool.ReadAttachmentTool;
import qg.qgent.chat.dto.MessageSendRequest;
import qg.qgent.chat.event.DirectMessageSentEvent;
import qg.qgent.chat.event.MessageSentEvent;
import qg.qgent.chat.service.ChatHistoryService;
import qg.qgent.chat.service.CrossGroupAccessService;
import qg.qgent.chat.service.MessageService;
import qg.qgent.chat.tool.ChatHistorySearchTool;
import qg.qgent.chat.tool.CrossGroupTools;
import qg.qgent.github.tool.GitHubCapability;
import qg.qgent.github.tool.GitHubTools;
import qg.qgent.harness.briefing.BriefingService;
import qg.qgent.harness.briefing.BriefingService.Scope;
import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.harness.entity.RunStatus;
import qg.qgent.harness.model.ModelMessage;
import qg.qgent.harness.service.AgentRunService;
import qg.qgent.harness.tool.Tool;
import qg.qgent.harness.tool.ToolCallback;
import qg.qgent.harness.tool.ToolParam;
import qg.qgent.harness.tool.ToolRegistry;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.mcp.tool.McpToolCallbacks;
import qg.qgent.memory.service.AgentMemoryService;
import qg.qgent.memory.service.GroupMemoryExtractionService;
import qg.qgent.memory.service.GroupMemoryService;
import qg.qgent.memory.service.ProjectMemoryService;
import qg.qgent.memory.tool.AgentMemoryTools;
import qg.qgent.memory.tool.GroupMemoryTools;
import qg.qgent.memory.tool.MemoryTools;
import qg.qgent.memory.tool.MemoryWriteTools;
import qg.qgent.sandbox.service.SandboxCommands;
import qg.qgent.sandbox.service.SandboxFiles;
import qg.qgent.sandbox.service.SandboxSessionManager;
import qg.qgent.sandbox.tool.CodingTools;
import qg.qgent.skill.service.SkillService;
import qg.qgent.skill.tool.ActivateSkillTool;
import qg.qgent.workspace.service.WorkSession;
import qg.qgent.workspace.service.WorkspaceRepositoryService;
import qg.qgent.workspace.service.WorkspaceSelection;
import qg.qgent.workspace.service.WorkspaceService;
import qg.qgent.workspace.tool.WorkspaceTools;

import java.util.*;
import java.util.concurrent.Executor;

/**
 * Starts the run of one project Agent and coordinates its temporary, one-level child conversations.
 */
@Service
public class AgentChatService {
    private static final Set<String> CHILD_CONTROL_TOOLS =
        Set.of(
            "start_subagent",
            "wait_subagent",
            "get_subagent_result",
            "message_subagent",
            "continue_subagent",
            "stop_subagent",
            "cancel_agent");
    private final GitHubCapability github;
    private final SandboxCommands workspaceCommands;
    private final WorkspaceService workspaces;
    private final WorkspaceRepositoryService repositories;
    private final AgentLoop loop;
    private final ObjectMapper json;
    private final BriefingService briefing;
    private final SkillService skills;
    private final ChatHistoryService history;
    private final MessageService messages;
    private final AgentRunService runService;
    private final AgentStreamService streams;
    private final SandboxSessionManager sessions;
    private final SandboxFiles files;
    private final AttachmentService attachments;
    private final ProjectMemoryService memories;
    private final GroupMemoryService groupMemory;
    private final GroupMemoryExtractionService groupMemoryMaintenance;
    private final AgentMemoryService agentMemories;
    private final CrossGroupAccessService crossGroup;
    private final AgentService agents;
    private final AgentApplicationService agentApplications;
    private final McpToolCallbacks mcp;
    private final Executor childExecutor;
    private final int maxSubagents;

    public AgentChatService(
        GitHubCapability github,
        SandboxCommands workspaceCommands,
        WorkspaceService workspaces,
        WorkspaceRepositoryService repositories,
        AgentLoop loop,
        ObjectMapper json,
        BriefingService briefing,
        SkillService skills,
        ChatHistoryService history,
        MessageService messages,
        AgentRunService runService,
        AgentStreamService streams,
        SandboxSessionManager sessions,
        SandboxFiles files,
        AttachmentService attachments,
        ProjectMemoryService memories,
        GroupMemoryService groupMemory,
        GroupMemoryExtractionService groupMemoryMaintenance,
        AgentMemoryService agentMemories,
        CrossGroupAccessService crossGroup,
        AgentService agents,
        AgentApplicationService agentApplications,
        McpToolCallbacks mcp,
        @Qualifier("agentSubagentExecutor") Executor childExecutor,
        @Value("${app.agent.max-subagents:8}") int maxSubagents) {
        if (maxSubagents < 1) throw new IllegalArgumentException("max-subagents 必须大于 0");
        this.github = github;
        this.workspaceCommands = workspaceCommands;
        this.workspaces = workspaces;
        this.repositories = repositories;
        this.loop = loop;
        this.json = json;
        this.briefing = briefing;
        this.skills = skills;
        this.history = history;
        this.messages = messages;
        this.runService = runService;
        this.streams = streams;
        this.sessions = sessions;
        this.files = files;
        this.attachments = attachments;
        this.memories = memories;
        this.groupMemory = groupMemory;
        this.groupMemoryMaintenance = groupMemoryMaintenance;
        this.agentMemories = agentMemories;
        this.crossGroup = crossGroup;
        this.agents = agents;
        this.agentApplications = agentApplications;
        this.mcp = mcp;
        this.childExecutor = childExecutor;
        this.maxSubagents = maxSubagents;
    }

    /**
     * Runs one conversation for a committed @Agent message and replies as that same Agent.
     */
    public void replyToMention(MessageSentEvent event, Long agentId, String instruction) {
        reply(
            new TaskRun(agentId, event.projectId(), event.groupId(), event.actor(), event.messageId(), false),
            instruction);
    }

    /**
     * 私聊里用户直接发来的消息：不需要提及，也只会回给这条会话的双方。
     */
    public void replyToDirectMessage(DirectMessageSentEvent event, String instruction) {
        reply(
            new TaskRun(
                event.agentId(),
                event.projectId(),
                event.conversationId(),
                event.actor(),
                event.messageId(),
                true),
            instruction);
    }

    private void reply(TaskRun task, String instruction) {
        Outcome outcome = runTask(task, instruction);
        MessageSendRequest reply = new MessageSendRequest();
        reply.setType("TEXT");
        reply.setContent(Map.of("text", outcome.text()));
        reply.setReplyToId(task.triggerMessageId());
        reply.setClientMessageId("qgent-reply:" + outcome.runId());
        if (task.direct()) {
            messages.replyInConversation(task.agentId(), task.groupId(), reply);
        } else {
            messages.sendAsAgent(task.agentId(), task.groupId(), reply);
        }
    }

    /**
     * 共享运行入口：供聊天适配层与离线评测共用，只负责执行并收尾根运行，不向聊天投递回复消息。
     */
    public Outcome runTask(TaskRun task, String instruction) {
        // 未启用或不属于本项目的 Agent 不产生运行，也不产生回复。
        AgentEntity agent = agents.requireActiveProjectAgent(task.projectId(), task.agentId());
        AgentRunEntity root =
            runService.startRoot(
                task.agentId(),
                task.projectId(),
                task.groupId(),
                task.triggerMessageId(),
                task.actorId(),
                instruction);
        RunStreamSink sink = streams.sink(root);
        WorkContext work =
            new WorkContext(
                task.actorId(),
                task.projectId(),
                task.groupId(),
                task.agentId(),
                agent,
                root.getId(),
                task.direct());
        RunLifecycle lifecycle = new RunLifecycle(runService, sessions, work);
        work.children =
            new ChildRuns(
                root.getId(),
                maxSubagents,
                childExecutor,
                runService,
                lifecycle,
                work::isCancelled,
                loop,
                child -> callbacks(work, child, conversationTools(work, child, false)),
                streams::sink);
        ChildRuns children = work.children;
        Thread rootWorker = Thread.currentThread();
        boolean wasInterrupted = rootWorker.isInterrupted();
        runService.registerCancellationSignal(root.getId(), () -> lifecycle.cancelled(root));
        RunStatus status = RunStatus.FAILED;
        String resultText = "Agent 执行失败，请稍后重试";
        int rounds = 0;
        try {
            String input =
                userInput(
                    task.actorId(),
                    task.projectId(),
                    task.groupId(),
                    task.agentId(),
                    Scope.ROOT,
                    instruction,
                    task.direct());
            ModelMessage.UserMessage user = new ModelMessage.UserMessage(input);
            AgentLoop.Conversation conversation =
                loop.conversation(briefing.systemPrompt(agent, Scope.ROOT, task.direct()), List.of(user));
            ChildRuns.Execution execution = new ChildRuns.Execution(root, conversation, null, ".");
            ConversationTools tools = conversationTools(work, execution, true);
            AgentLoop.Result result =
                loop.run(
                    root.getId(),
                    conversation,
                    () -> callbacks(work, execution, tools),
                    work::isCancelled,
                    children.updates(),
                    sink);
            status = result.success() ? RunStatus.SUCCEEDED : RunStatus.FAILED;
            resultText = result.text();
            rounds = result.rounds();
            root.addMetrics(result.metrics(), loop.modelName());
        } catch (RuntimeException exception) {
            status = work.isCancelled() ? RunStatus.CANCELLED : RunStatus.FAILED;
            resultText = failure(exception);
        } finally {
            lifecycle.finish(root, status, resultText, rounds);
            // finishRoot 在并发取消时可能把运行改为 CANCELLED，终态事件以记录为准。
            sink.finished();
            runService.unregisterCancellationSignal(root.getId());
            Thread.interrupted();
            if (wasInterrupted) rootWorker.interrupt();
        }
        AgentLoop.Metrics totalMetrics =
            new AgentLoop.Metrics(
                root.getPromptTokens() == null ? 0L : root.getPromptTokens(),
                root.getCompletionTokens() == null ? 0L : root.getCompletionTokens(),
                root.getCachedTokens() == null ? 0L : root.getCachedTokens(),
                root.getReasoningTokens() == null ? 0L : root.getReasoningTokens(),
                root.getTtftMs(),
                root.getModelTimeMs() == null ? 0L : root.getModelTimeMs(),
                root.getToolTimeMs() == null ? 0L : root.getToolTimeMs(),
                root.getToolCalls() == null ? 0 : root.getToolCalls(),
                root.getFailedToolCalls() == null ? 0 : root.getFailedToolCalls());
        return new Outcome(
            root.getId(),
            root.getStatus(),
            answer(root),
            root.getRounds() == null ? rounds : root.getRounds(),
            totalMetrics);
    }

    public record TaskRun(
        Long agentId,
        Long projectId,
        Long groupId,
        Long actorId,
        Long triggerMessageId,
        boolean direct) {}

    public record Outcome(
        Long runId, RunStatus status, String text, int rounds, AgentLoop.Metrics metrics) {}

    private List<ToolCallback> callbacks(
        WorkContext work, ChildRuns.Execution execution, ConversationTools tools) {
        if (work.isCancelled() || execution.isStopRequested())
            throw new IllegalStateException("Agent 运行已取消");
        return tools.callbacks();
    }

    private ConversationTools conversationTools(
        WorkContext work, ChildRuns.Execution execution, boolean canDelegate) {
        return (ConversationTools)
            work.toolsets.computeIfAbsent(
                execution.run.getId(),
                ignored -> new ConversationTools(work, execution, canDelegate));
    }

    /**
     * 用户消息 = 本轮任务 + 与主运行同一套渲染的上下文正文。
     */
    private String userInput(
        Long actor,
        Long projectId,
        Long groupId,
        Long agentId,
        Scope scope,
        String instruction,
        boolean direct) {
        return instruction
            + "\n\n"
            + briefing.context(actor, projectId, groupId, agentId, scope, direct);
    }

    private String answer(AgentRunEntity root) {
        return root.getResult() == null ? "Agent 本次运行未返回文本" : root.getResult();
    }

    private String failure(RuntimeException exception) {
        String message = exception.getMessage();
        return "Agent 执行失败：" + (message == null || message.isBlank() ? "请稍后重试" : message);
    }

    private Long requiredId(String value) {
        try {
            return Long.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("子 Agent runId 无效");
        }
    }

    private int bounded(Integer value, int fallback, int min, int max) {
        int result = value == null ? fallback : value;
        if (result < min || result > max)
            throw new IllegalArgumentException("参数超出范围：" + min + "～" + max);
        return result;
    }

    /**
     * Per-run tools keep workspace selection, directory and delegation capability local to that conversation.
     */
    public class ConversationTools {
        private final WorkContext work;
        private final ChildRuns.Execution execution;
        private final boolean canDelegate;
        private final ActivateSkillTool skills;
        private final ChatHistorySearchTool history;
        private final CrossGroupTools crossGroupTools;
        private final ReadAttachmentTool attachmentTools;
        private final MemoryTools memory;
        private final GroupMemoryTools groupMemoryTools;
        private final MemoryWriteTools memoryWrite;
        private final AgentMemoryTools agentMemory;
        private final AgentApplicationTools applicationTools;
        private final GitHubTools githubTools;
        private final CodingTools codingTools;
        private final WorkspaceTools workspaceTools;
        private final List<ToolCallback> availableTools;

        private ConversationTools(
            WorkContext work, ChildRuns.Execution execution, boolean canDelegate) {
            this.work = work;
            this.execution = execution;
            this.canDelegate = canDelegate;
            this.skills =
                new ActivateSkillTool(
                    AgentChatService.this.skills,
                    work.actorId,
                    work.projectId);
            this.history =
                new ChatHistorySearchTool(
                    AgentChatService.this.history,
                    work.actorId,
                    work.projectId,
                    work.groupId,
                    5);
            this.crossGroupTools =
                new CrossGroupTools(
                    AgentChatService.this.crossGroup,
                    AgentChatService.this.groupMemory,
                    AgentChatService.this.history,
                    work.actorId,
                    work.projectId,
                    work.groupId,
                    work.agentId);
            this.attachmentTools =
                new ReadAttachmentTool(
                    AgentChatService.this.attachments,
                    work.actorId,
                    work.projectId,
                    execution.conversation::stageImage);
            this.memory = new MemoryTools(memories, work.actorId, work.projectId);
            // 私聊没有群记忆：不装配群记忆工具，避免把私聊内容当群结论或提交到不存在的群。
            this.groupMemoryTools =
                work.direct
                    ? null
                    : new GroupMemoryTools(
                        groupMemoryMaintenance,
                        work.actorId,
                        work.projectId,
                        work.groupId);
            // 子 Agent 只读项目记忆；私聊不写共享的项目记忆。
            this.memoryWrite =
                canDelegate && !work.direct
                    ? new MemoryWriteTools(memories, work.actorId, work.projectId)
                    : null;
            this.agentMemory =
                new AgentMemoryTools(agentMemories, work.projectId, work.agentId);
            // 申请与审批只装配给主 Agent：子 Agent 不能替用户提交申请，也不能代替管理员审批。
            this.applicationTools =
                canDelegate
                    ? new AgentApplicationTools(
                    agentApplications, work.actorId, work.projectId)
                    : null;
            WorkSession session =
                new WorkspaceSession(work, execution, workspaces, sessions, runService);
            this.githubTools = github.tools(work.actorId, work.projectId, session);
            this.workspaceTools =
                new WorkspaceTools(
                    work.actorId,
                    work.projectId,
                    workspaces,
                    repositories,
                    workspaceCommands,
                    session);
            this.codingTools = new CodingTools(session, files);
            ToolRegistry registry = new ToolRegistry(json);
            registry.register(
                this,
                skills,
                history,
                crossGroupTools,
                attachmentTools,
                memory,
                agentMemory,
                workspaceTools,
                githubTools,
                codingTools);
            if (groupMemoryTools != null) registry.register(groupMemoryTools);
            if (memoryWrite != null) registry.register(memoryWrite);
            if (applicationTools != null) registry.register(applicationTools);
            List<ToolCallback> available = new ArrayList<>(registry.callbacks());
            if (!canDelegate)
                available.removeIf(callback -> CHILD_CONTROL_TOOLS.contains(callback.name()));
            available.addAll(mcp.forRun(work.projectId, work.agentId, work.actorId));
            this.availableTools = List.copyOf(available);
        }

        private List<ToolCallback> callbacks() {
            return availableTools;
        }

        @Tool(
            name = "start_subagent",
            description = "后台启动一个独立子 Agent 并立即返回 runId。仅主 Agent 可调用；说明明确任务、修改范围、必要背景和期望结果。")
        public ToolResult startSubagent(
            @ToolParam(description = "子任务和必要背景") String instruction,
            @ToolParam(description = "可选初始 Workspace ID；未提供时继承当前选择", required = false)
            String workspaceId,
            @ToolParam(description = "可选初始工作目录；未提供时继承当前目录", required = false)
            String directory) {
            if (!canDelegate) return ToolResult.error("DELEGATION_NOT_ALLOWED", "子 Agent 不允许继续委派");
            if (instruction == null || instruction.isBlank())
                return ToolResult.error("INVALID_ARGUMENT", "子任务内容不能为空");
            Long selected =
                workspaceId == null || workspaceId.isBlank()
                    ? execution.workspace.workspaceId()
                    : workspaces
                    .require(work.projectId, requiredId(workspaceId))
                    .getId();
            String childDirectory =
                directory == null || directory.isBlank()
                    ? execution.workspace.directory()
                    : WorkspaceSelection.resolveDirectory(".", directory);
            return work.children.start(
                execution.run,
                instruction,
                selected,
                childDirectory,
                text ->
                    loop.conversation(
                        briefing.systemPrompt(work.agent, Scope.CHILD, work.direct),
                        List.of(
                            new ModelMessage.UserMessage(
                                userInput(
                                    work.actorId,
                                    work.projectId,
                                    work.groupId,
                                    work.agentId,
                                    Scope.CHILD,
                                    text,
                                    work.direct)))));
        }

        @Tool(name = "wait_subagent", description = "等待指定子 Agent 最多 30 秒；等待超时返回当前状态，可再次等待。")
        public ToolResult waitSubagent(
            @ToolParam(description = "start_subagent 返回的 runId") String runId,
            @ToolParam(description = "等待秒数，0～30，默认 10") Integer waitSeconds) {
            return work.children.waitFor(runId, bounded(waitSeconds, 10, 0, 30));
        }

        @Tool(name = "get_subagent_result", description = "读取子 Agent 当前状态和最近结果，不等待。")
        public ToolResult getSubagentResult(
            @ToolParam(description = "子 Agent runId") String runId) {
            return work.children.status(runId, false);
        }

        @Tool(name = "message_subagent", description = "向指定子 Agent 补充要求或背景。忙碌时会排入同一对话；已结束时续接原对话。")
        public ToolResult messageSubagent(
            @ToolParam(description = "子 Agent runId") String runId,
            @ToolParam(description = "补充要求或消息") String message) {
            return work.children.handoff(runId, message);
        }

        @Tool(name = "continue_subagent", description = "续接同一个子 Agent 的原对话和工具上下文，继续执行补充任务。")
        public ToolResult continueSubagent(
            @ToolParam(description = "子 Agent runId") String runId,
            @ToolParam(description = "继续要求") String instruction) {
            return work.children.handoff(runId, instruction);
        }

        @Tool(name = "stop_subagent", description = "停止指定子 Agent；不会取消主运行或其他子 Agent。")
        public ToolResult stopSubagent(@ToolParam(description = "子 Agent runId") String runId) {
            return work.children.stop(runId);
        }

        @Tool(name = "cancel_agent", description = "停止指定聊天消息启动的整次根运行；取消会传播给所属子 Agent。")
        public ToolResult cancelAgent(@ToolParam(description = "触发执行的用户消息 ID") String messageId) {
            int cancelled =
                runService.cancel(
                    work.actorId,
                    work.projectId,
                    work.groupId,
                    requiredId(messageId));
            ToolResult result = ToolResult.ok();
            result.put("cancelledRuns", cancelled);
            return result;
        }
    }
}

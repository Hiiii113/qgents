package qg.qgent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.entity.AgentRunEntity;
import qg.qgent.agent.service.AgentRunService;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.agent.mapper.AgentRunMapper;
import qg.qgent.agent.tool.AttachmentMediaLoader;
import qg.qgent.agent.tool.GitHubCapability;
import qg.qgent.chat.dto.GroupContext;
import qg.qgent.chat.event.MessageSentEvent;
import qg.qgent.chat.service.ContextService;
import qg.qgent.chat.service.MessageService;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.sandbox.service.SandboxSessionManager;
import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.service.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentChatServiceTest {
    private final UUID projectId = UUID.randomUUID(), groupId = UUID.randomUUID(), actor = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
    private final AgentLoop loop = mock(AgentLoop.class);
    private final AgentRunService runService = mock(AgentRunService.class);
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final SandboxSessionManager sessions = mock(SandboxSessionManager.class);
    private final WorkspaceCommands commands = mock(WorkspaceCommands.class);
    private final WorkspaceCodeAccess code = mock(WorkspaceCodeAccess.class);
    private final ContextService context = mock(ContextService.class);
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final MessageService messages = mock(MessageService.class);
    private final AgentEntity agent = new AgentEntity();
    private final qg.qgent.github.service.GitHubRepositoryService repositories = mock(qg.qgent.github.service.GitHubRepositoryService.class);
    private AgentChatService service;
    private UUID rootRunId;

    @BeforeEach
    void setup() {
        doAnswer(invocation -> {
            AgentRunEntity run = invocation.getArgument(0);
            if (run.getParentRunId() == null) rootRunId = run.getId();
            return 1;
        }).when(runs).insert(any(AgentRunEntity.class));
        var agents = mock(AgentMapper.class);
        var projects = mock(ProjectMapper.class);
        var attachments = mock(AttachmentMediaLoader.class);
        var project = new ProjectEntity();
        project.setTeamId(UUID.randomUUID());
        agent.setId(agentId); agent.setTeamId(project.getTeamId()); agent.setName("通用 Agent");
        agent.setStatus("ACTIVE"); agent.setVisibility("TEAM");
        when(agents.selectById(agentId)).thenReturn(agent);
        when(projects.selectById(projectId)).thenReturn(project);
        var group = new GroupContext(); group.setConversation(List.of());
        when(context.buildForGroup(actor, projectId, groupId, 30)).thenReturn(group);
        when(attachments.load(actor, projectId, List.of())).thenReturn(new AttachmentMediaLoader.Result(List.of(), ""));
        when(workspaces.create(projectId)).thenReturn(workspace(workspaceId));
        when(workspaces.require(projectId, workspaceId)).thenReturn(workspace(workspaceId));
        when(commands.start(any(), any(), anyList(), anyInt(), anyInt())).thenReturn(Map.of("ok", true, "exitCode", 0));
        service = new AgentChatService(new GitHubCapability(commands, repositories), commands, workspaces,
                loop, agents, runs, projects, context, messages, runService, sessions, code,
                mock(WorkspaceCodeWriter.class), attachments, new ObjectMapper(), 3, 8);
    }

    @Test
    void commandControlDoesNotCreateEnvironmentWithoutAnExecution() {
        runTools(available -> assertThatThrownBy(() -> find(available, "command_output")
                .call("{\"executionId\":\"" + UUID.randomUUID() + "\"}"))
                .hasStackTraceContaining("请先 run_command"));
        reply("读取命令输出");
        verifyNoInteractions(workspaces, sessions, commands);
    }

    @Test
    void commandControlCallbacksUseRunningHandleAndOutputCursors() {
        UUID execution = UUID.randomUUID();
        when(commands.start(any(), any(), anyList(), anyInt(), anyInt()))
                .thenReturn(Map.of("ok", true, "running", true, "executionId", execution.toString()));
        when(commands.output(workspaceId, execution, 30, 40, 16000, 0))
                .thenReturn(Map.of("ok", true, "running", true, "stdout", Map.of("text", "progress", "nextOffset", 38)));
        when(commands.cancel(workspaceId, execution)).thenReturn(Map.of("ok", false, "status", "CANCELLED"));
        runTools(available -> {
            assertThat(find(available, "run_command").call("{\"command\":[\"npm\",\"test\"],\"waitSeconds\":0}"))
                    .contains(execution.toString());
            assertThat(find(available, "command_output").call("{\"executionId\":\"" + execution
                    + "\",\"stdoutOffset\":30,\"stderrOffset\":40,\"waitSeconds\":0}"))
                    .contains("progress", "nextOffset");
            assertThat(find(available, "cancel_command").call("{\"executionId\":\"" + execution + "\"}"))
                    .contains("CANCELLED");
        });
        reply("执行并取消");
        verify(commands).start(workspaceId, null, List.of("npm", "test"), 600, 0);
        verify(commands).output(workspaceId, execution, 30, 40, 16000, 0);
        verify(commands).cancel(workspaceId, execution);
    }

    @Test
    void ordinaryAnswerDoesNotPrepareEnvironmentDespiteAvailableCodeTools() {
        when(loop.run(anyString(), anyList(), any(), any())).thenAnswer(invocation -> {
            Supplier<List<ToolCallback>> available = invocation.getArgument(2);
            assertThat(available.get()).extracting(t -> t.getToolDefinition().name())
                    .contains("read_file", "run_command").doesNotContain("github_clone", "prepare_workspace", "create_task", "use_task", "list_tasks", "update_task");
            return new AgentLoop.Result(true, "你好", 1);
        });
        reply("你好");
        verifyNoInteractions(workspaces, sessions, commands, code);
        verify(messages).sendAsAgent(eq(groupId), eq(agentId), argThat(r -> "你好".equals(r.getContent().get("text"))));
    }

    @Test
    void historySearchDoesNotPrepareEnvironment() {
        when(context.searchChatHistory(actor, projectId, groupId, "需求", 10)).thenReturn(List.of());
        runTools(available -> assertThat(find(available, "search_chat_history").call("{\"query\":\"需求\"}"))
                .contains("\"ok\":true"));
        reply("查找之前的需求");
        verifyNoInteractions(workspaces, sessions, commands);
    }

    @Test
    void readingReplyTargetReturnsFullTextWithoutCodeEnvironment() {
        UUID messageId = UUID.randomUUID();
        var original = new qg.qgent.chat.dto.ContextMessage(1L, "TEXT", "USER", actor.toString(), "需求".repeat(500));
        when(context.readChatMessage(actor, projectId, groupId, messageId)).thenReturn(original);
        runTools(available -> assertThat(find(available, "read_chat_message")
                .call("{\"messageId\":\"" + messageId + "\"}")).contains("需求".repeat(500)));
        reply("继续原消息的讨论");
        verifyNoInteractions(workspaces, sessions, commands);
    }

    @Test
    void localGithubToolPreparesEnvironmentLazily() {
        agent.setGithubEnabled(true);
        when(repositories.repositoryToken(actor, projectId, agent.getTeamId(), "owner/repo")).thenReturn("test-token");
        runTools(available -> find(available, "github_git")
                .call("{\"command\":[\"status\"],\"directory\":\".\",\"repository\":\"owner/repo\"}"));
        reply("检查仓库");
        verify(sessions).acquire(rootRunId, projectId, workspaceId);
        verify(commands).run(eq(workspaceId), eq("."), eq(List.of("git", "status")), anyMap());
    }

    @Test
    void firstCodeCallCreatesEnvironmentOnceAndPreservesCommandFailure() {
        when(commands.start(eq(workspaceId), eq("chosen"), anyList(), eq(600), eq(10)))
                .thenReturn(Map.of("ok", false, "exitCode", 1, "stderr", "build failed"));
        runTools(available -> {
            assertThat(find(available, "run_command").call("{\"command\":[\"git\",\"status\"],\"directory\":\"chosen\"}"))
                    .contains("build failed");
            find(available, "glob_files").call("{}");
        });
        reply("实现功能");
        verify(workspaces).create(projectId);
        verify(sessions).acquire(rootRunId, projectId, workspaceId);
        verify(sessions).release(workspaceId, rootRunId);
        verify(runs, atLeastOnce()).updateById(argThat((qg.qgent.agent.entity.AgentRunEntity run) -> workspaceId.equals(run.getWorkspaceId())));
    }

    @Test
    void selectingWorkspaceDoesNotStartSandbox() {
        runTools(available -> find(available, "use_workspace").call("{\"workspaceId\":\"" + workspaceId + "\"}"));
        reply("选择环境");
        verifyNoInteractions(sessions, commands);
        verify(workspaces, never()).create(any());
    }

    @Test
    void newRunCanReuseExistingWorkspace() {
        runTools(available -> {
            find(available, "use_workspace").call("{\"workspaceId\":\"" + workspaceId + "\"}");
            find(available, "run_command").call("{\"command\":[\"git\",\"status\"]}");
        });
        reply("新任务使用已有代码");
        verify(workspaces, never()).create(any());
        verify(sessions).acquire(rootRunId, projectId, workspaceId);
    }

    @Test
    void runCanSwitchEnvironmentsAndReleaseAllOpenedSandboxes() {
        UUID second = UUID.randomUUID();
        when(workspaces.require(projectId, second)).thenReturn(workspace(second));
        runTools(available -> {
            find(available, "run_command").call("{\"command\":[\"pwd\"]}");
            find(available, "use_workspace").call("{\"workspaceId\":\"" + second + "\"}");
            find(available, "run_command").call("{\"command\":[\"pwd\"]}");
        });
        reply("跨环境工作");
        verify(sessions).acquire(rootRunId, projectId, workspaceId);
        verify(sessions).acquire(rootRunId, projectId, second);
        verify(sessions).release(workspaceId, rootRunId);
        verify(sessions).release(second, rootRunId);
    }

    @Test
    void unavailableGithubCapabilityDoesNotBreakChatOrPrepareEnvironment() {
        agent.setGithubEnabled(true);
        runTools(available -> assertThatThrownBy(() -> find(available, "github_request")
                .call("{\"method\":\"GET\",\"path\":\"/repos/owner/repo\"}"))
                .hasStackTraceContaining("凭证未配置"));
        reply("查仓库");
        verifyNoInteractions(workspaces, sessions, commands);
    }

    @Test
    void environmentFailureDoesNotExecuteCommand() {
        when(sessions.acquire(any(), eq(projectId), eq(workspaceId))).thenThrow(new IllegalStateException("worker unavailable"));
        runTools(available -> assertThatThrownBy(() -> find(available, "run_command")
                .call("{\"command\":[\"pwd\"]}")) .hasStackTraceContaining("worker unavailable"));
        reply("读取代码");
        verifyNoInteractions(commands);
        verify(sessions, never()).release(any(), any());
    }

    @Test
    void childAgentUsesRootEnvironmentAndOnlyRootReleasesIt() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(loop.run(anyString(), anyList(), any(), any())).thenAnswer(invocation -> {
            Supplier<List<ToolCallback>> available = invocation.getArgument(2);
            if (calls.getAndIncrement() == 0) {
                find(available, "run_command").call("{\"command\":[\"pwd\"]}");
                assertThat(find(available, "call_agent").call("{\"agentId\":\"" + agentId
                        + "\",\"instruction\":\"读取文件\"}")).contains("\"ok\":true");
                assertThat(qg.qgent.sandbox.dto.WorkerExecutionTraceContext.currentAgentRunId()).isEqualTo(rootRunId);
            } else {
                assertThat(qg.qgent.sandbox.dto.WorkerExecutionTraceContext.currentAgentRunId()).isNotEqualTo(rootRunId);
                find(available, "glob_files").call("{}");
                verify(sessions, never()).release(any(), any());
            }
            return new AgentLoop.Result(true, "结果", 1);
        });
        reply("委托检查代码");
        verify(sessions).acquire(rootRunId, projectId, workspaceId);
        verify(sessions).release(workspaceId, rootRunId);
        verify(runs, times(2)).insert(any(AgentRunEntity.class));
        assertThat(qg.qgent.sandbox.dto.WorkerExecutionTraceContext.currentAgentRunId()).isNull();
    }

    @Test
    void cancellingRootStopsFurtherCodeCallsAndReturnsCancellationMessage() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        when(runs.selectById(any(UUID.class))).thenAnswer(invocation -> {
            var root = new AgentRunEntity();
            root.setStatus(cancelled.get() ? "CANCELLING" : "RUNNING");
            return root;
        });
        when(loop.run(anyString(), anyList(), any(), any())).thenAnswer(invocation -> {
            Supplier<List<ToolCallback>> available = invocation.getArgument(2);
            ToolCallback command = find(available, "run_command");
            command.call("{\"command\":[\"pwd\"]}");
            cancelled.set(true);
            assertThat(((java.util.function.BooleanSupplier) invocation.getArgument(3)).getAsBoolean()).isTrue();
            assertThatThrownBy(() -> command.call("{\"command\":[\"pwd\"]}"))
                    .hasStackTraceContaining("Agent 运行已取消");
            return new AgentLoop.Result(true, "不应作为完成结果", 1);
        });
        reply("修改代码");
        verify(commands).start(any(), any(), anyList(), anyInt(), anyInt());
        verify(sessions).release(workspaceId, rootRunId);
        verify(runs, atLeastOnce()).updateById(argThat((AgentRunEntity run) -> "CANCELLED".equals(run.getStatus())));
        verify(messages).sendAsAgent(eq(groupId), eq(agentId),
                argThat(request -> "Agent 运行已取消".equals(request.getContent().get("text"))));
    }

    @Test
    void failureAfterOpeningEnvironmentStillReleasesIt() {
        when(loop.run(anyString(), anyList(), any(), any())).thenAnswer(invocation -> {
            Supplier<List<ToolCallback>> available = invocation.getArgument(2);
            find(available, "run_command").call("{\"command\":[\"pwd\"]}");
            throw new IllegalStateException("model unavailable");
        });
        reply("修改代码");
        verify(sessions).release(workspaceId, rootRunId);
        verify(runs, atLeastOnce()).updateById(argThat((AgentRunEntity run) -> "FAILED".equals(run.getStatus())));
        verify(messages).sendAsAgent(eq(groupId), eq(agentId),
                argThat(request -> request.getContent().get("text").toString().contains("model unavailable")));
    }

    @Test
    void cancellationToolTargetsChatMessage() {
        UUID messageId = UUID.randomUUID();
        when(runService.cancel(actor, projectId, groupId, messageId)).thenReturn(1);
        runTools(available -> assertThat(find(available, "cancel_agent")
                .call("{\"messageId\":\"" + messageId + "\"}")).contains("\"cancelledRuns\":1"));
        reply("停止之前的工作");
        verify(runService).cancel(actor, projectId, groupId, messageId);
        verifyNoInteractions(workspaces, sessions, commands);
    }

    private void runTools(java.util.function.Consumer<Supplier<List<ToolCallback>>> action) {
        when(loop.run(anyString(), anyList(), any(), any())).thenAnswer(invocation -> {
            action.accept(invocation.getArgument(2));
            return new AgentLoop.Result(true, "结果", 2);
        });
    }

    private void reply(String instruction) {
        service.replyToMention(new MessageSentEvent(actor, projectId, groupId, UUID.randomUUID(), List.of()), agentId, instruction);
    }

    private WorkspaceEntity workspace(UUID id) {
        WorkspaceEntity workspace = new WorkspaceEntity(); workspace.setId(id); workspace.setProjectId(projectId);
        workspace.setStatus("READY"); workspace.setUpdatedAt(LocalDateTime.now()); return workspace;
    }

    private ToolCallback find(Supplier<List<ToolCallback>> available, String name) {
        return available.get().stream().filter(t -> name.equals(t.getToolDefinition().name())).findFirst().orElseThrow();
    }
}

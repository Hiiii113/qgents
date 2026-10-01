package qg.qgent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.AgentRunEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.AgentRunMapper;
import qg.qgent.runtime.tools.AttachmentMediaLoader;
import qg.qgent.dto.GroupContext;
import qg.qgent.service.MessageSentEvent;
import qg.qgent.service.ContextService;
import qg.qgent.service.MessageService;
import qg.qgent.service.MergeRequestService;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.orchestration.worker.SandboxSessionManager;
import qg.qgent.dto.TaskResponse;
import qg.qgent.entity.TaskEntity;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.service.TaskService;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.orchestration.tool.DevelopmentCommandPort;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;
import qg.qgent.orchestration.tool.WorkspaceDiffAccess;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentChatServiceTest {
    private final UUID projectId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final ChatModel model = mock(ChatModel.class);
    private final AgentMapper agents = mock(AgentMapper.class);
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final ProjectRepositoryMapper repositories = mock(ProjectRepositoryMapper.class);
    private final ContextService context = mock(ContextService.class);
    private final MessageService messages = mock(MessageService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final MergeRequestService mergeRequests = mock(MergeRequestService.class);
    private final SandboxSessionManager sessions = mock(SandboxSessionManager.class);
    private final AttachmentMediaLoader attachments = mock(AttachmentMediaLoader.class);
    private AgentChatService service;
    private TaskEntity task;

    @BeforeEach
    void setup() {
        UUID teamId = UUID.randomUUID();
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(teamId);
        when(projects.selectById(projectId)).thenReturn(project);
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setTeamId(teamId);
        agent.setStatus("ACTIVE");
        agent.setVisibility("TEAM");
        agent.setName("通用 Agent");
        when(agents.selectById(agentId)).thenReturn(agent);
        when(context.buildForGroup(actor, projectId, groupId, 30)).thenReturn(new GroupContext());
        when(attachments.load(eq(actor), eq(projectId), any()))
                .thenReturn(new AttachmentMediaLoader.Result(List.of(), ""));
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().model("test").build());
        service = new AgentChatService(new AgentLoop(model, new ObjectMapper(), 5), agents, runs, projects,
                repositories, context, messages, tasks, taskMapper, mergeRequests,
                mock(WorkspaceRepositoryMapper.class), sessions, mock(WorkspaceCodeAccess.class),
                mock(WorkspaceCodeWriter.class), mock(WorkspaceDiffAccess.class), attachments,
                mock(DevelopmentCommandPort.class), new ObjectMapper(), 3, 8);
        task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setRequirementGroupId(groupId);
        task.setWorkspaceId(workspaceId);
        task.setRequirement("修改代码");
        task.setStatus("PENDING");
        TaskResponse response = new TaskResponse();
        response.setId(taskId.toString());
        response.setWorkspaceId(workspaceId.toString());
        when(tasks.require(projectId, taskId, actor)).thenReturn(task);
        when(tasks.response(task)).thenReturn(response);
        when(taskMapper.selectById(taskId)).thenReturn(task);
    }

    @Test
    void ordinaryAgentQuestionDoesNotCreateTaskOrSubmitCode() {
        when(model.call(any(Prompt.class))).thenReturn(text("你好"));
        service.replyToMention(event(), agentId, "你好");
        verifyNoInteractions(tasks, sessions, mergeRequests);
        verify(messages).sendAsAgent(eq(groupId), eq(agentId), any());
    }

    @Test
    void startingAndContinuingTaskReuseItsWorkspaceWithoutAutomaticMr() {
        when(model.call(any(Prompt.class))).thenReturn(text("完成"));
        service.startTask(projectId, taskId, actor, agentId, null);
        task.setStatus("SUCCEEDED");
        service.startTask(projectId, taskId, actor, agentId, "继续修改");
        verify(sessions, times(2)).acquire(taskId, projectId, workspaceId);
        verify(sessions, times(2)).release(workspaceId, taskId);
        verify(tasks, never()).create(any(), any(), any());
        verifyNoInteractions(mergeRequests);
    }

    @Test
    void workspaceToolFailureCannotTurnTaskIntoSuccess() {
        TaskResponse response = tasks.response(task);
        when(tasks.create(eq(projectId), eq(actor), any())).thenReturn(response);
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(UUID.randomUUID());
        when(repositories.selectList(any())).thenReturn(List.of(repository));
        when(sessions.acquire(taskId, projectId, workspaceId)).thenThrow(new IllegalStateException("Worker 不可用"));
        when(model.call(any(Prompt.class))).thenReturn(
                tool("prepare_workspace", "{\"title\":\"修改\",\"requirement\":\"修改代码\"}"), text("暂时无法执行"));
        service.replyToMention(event(), agentId, "修改代码");
        verify(tasks).updateStatus(taskId, "FAILED");
        verify(tasks, never()).updateStatus(taskId, "SUCCEEDED");
    }

    @Test
    void explicitMrToolInvokesServiceDuringTaskLoop() {
        UUID repositoryId = UUID.randomUUID();
        when(model.call(any(Prompt.class))).thenReturn(tool("create_merge_request",
                "{\"repositoryId\":\"" + repositoryId + "\",\"title\":\"提交修改\",\"body\":\"说明\",\"targetBranch\":\"main\"}"),
                text("已提交"));
        service.startTask(projectId, taskId, actor, agentId, "提交 MR");
        verify(mergeRequests).create(projectId, actor, taskId, repositoryId, "main", "提交修改", "说明");
    }

    @Test
    void cancelledQueuedTaskDoesNotStartModelOrSandbox() {
        task.setStatus("CANCELLED");
        service.startTask(projectId, taskId, actor, agentId, null);
        verifyNoInteractions(model, sessions);
    }

    @Test
    void subagentUsesSameTaskWorkspaceAndHasSeparateRun() {
        when(model.call(any(Prompt.class))).thenReturn(tool("call_agent",
                "{\"agentId\":\"" + agentId + "\",\"instruction\":\"处理子问题\"}"), text("子问题完成"), text("完成"));
        service.startTask(projectId, taskId, actor, agentId, null);
        verify(sessions).acquire(taskId, projectId, workspaceId);
        ArgumentCaptor<AgentRunEntity> recorded = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(runs, times(2)).insert(recorded.capture());
        AgentRunEntity root = recorded.getAllValues().getFirst();
        AgentRunEntity child = recorded.getAllValues().getLast();
        assertThat(child.getParentRunId()).isEqualTo(root.getId());
        assertThat(child.getTaskId()).isEqualTo(taskId);
        assertThat(child.getInstruction()).isEqualTo("处理子问题");
        verifyNoInteractions(mergeRequests);
    }

    private MessageSentEvent event() {
        return new MessageSentEvent(actor, projectId, groupId, UUID.randomUUID(), List.of());
    }

    @Test
    void parentRunIsLinkedBeforeContinuingAfterChildCreatesWorkspace() {
        TaskResponse response = tasks.response(task);
        when(tasks.create(eq(projectId), eq(actor), any())).thenReturn(response);
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(UUID.randomUUID());
        when(repositories.selectList(any())).thenReturn(List.of(repository));
        java.util.ArrayList<AgentRunEntity> recorded = new java.util.ArrayList<>();
        when(runs.insert(any(AgentRunEntity.class))).thenAnswer(invocation -> {
            recorded.add(invocation.getArgument(0));
            return 1;
        });
        when(model.call(any(Prompt.class))).thenReturn(
                tool("call_agent", "{\"agentId\":\"" + agentId + "\",\"instruction\":\"修改代码\"}"),
                tool("prepare_workspace", "{\"title\":\"修改\",\"requirement\":\"修改代码\"}"),
                text("子任务完成")).thenAnswer(invocation -> {
                    assertThat(recorded.getFirst().getTaskId()).isEqualTo(taskId);
                    return text("完成");
                });
        service.replyToMention(event(), agentId, "委托子 Agent 修改代码");
        verify(sessions).acquire(taskId, projectId, workspaceId);
        verify(sessions).release(workspaceId, taskId);
        assertThat(recorded).hasSize(2);
    }

    private ChatResponse text(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private ChatResponse tool(String name, String arguments) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", name, arguments))).build())));
    }
}

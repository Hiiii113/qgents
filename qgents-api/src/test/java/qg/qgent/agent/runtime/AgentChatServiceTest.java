package qg.qgent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import qg.qgent.agent.entity.AgentEntity;
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
import qg.qgent.task.dto.TaskResponse;
import qg.qgent.task.mapper.TaskMapper;
import qg.qgent.task.service.TaskService;
import qg.qgent.workspace.service.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentChatServiceTest {
    private final UUID projectId = UUID.randomUUID(), groupId = UUID.randomUUID(), actor = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID(), workspaceId = UUID.randomUUID(), taskId = UUID.randomUUID();
    private final AgentLoop loop = mock(AgentLoop.class);
    private final TaskService tasks = mock(TaskService.class);
    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final SandboxSessionManager sessions = mock(SandboxSessionManager.class);
    private final WorkspaceCommands commands = mock(WorkspaceCommands.class);
    private final MessageService messages = mock(MessageService.class);
    private AgentChatService service;
    private TaskResponse task;

    @BeforeEach
    void setup() {
        var agents = mock(AgentMapper.class);
        var projects = mock(ProjectMapper.class);
        var context = mock(ContextService.class);
        var attachments = mock(AttachmentMediaLoader.class);
        var project = new ProjectEntity();
        project.setTeamId(UUID.randomUUID());
        var agent = new AgentEntity();
        agent.setId(agentId);
        agent.setTeamId(project.getTeamId());
        agent.setName("通用 Agent");
        agent.setStatus("ACTIVE");
        agent.setVisibility("TEAM");
        when(agents.selectById(agentId)).thenReturn(agent);
        when(projects.selectById(projectId)).thenReturn(project);
        var group = new GroupContext();
        group.setConversation(List.of());
        when(context.buildForGroup(actor, projectId, groupId, 30)).thenReturn(group);
        when(attachments.load(actor, projectId, List.of())).thenReturn(new AttachmentMediaLoader.Result(List.of(), ""));
        task = new TaskResponse();
        task.setId(taskId.toString());
        task.setWorkspaceId(workspaceId.toString());
        when(tasks.create(eq(projectId), eq(actor), any())).thenReturn(task);
        service = new AgentChatService(mock(GitHubCapability.class), commands, loop, agents, mock(AgentRunMapper.class),
                projects, context, messages, tasks, taskMapper, sessions, mock(WorkspaceCodeAccess.class),
                mock(WorkspaceCodeWriter.class), attachments, mock(DevelopmentCommandPort.class), new ObjectMapper(), 3, 8);
    }

    @Test
    void agentAnswersWithoutCreatingTaskAndGithubToolsAreAbsentWhenNotConfigured() {
        when(loop.run(anyString(), anyList(), any(), any())).thenAnswer(invocation -> {
            Supplier<List<ToolCallback>> available = invocation.getArgument(2);
            assertThat(available.get()).extracting(t -> t.getToolDefinition().name()).doesNotContain("github_clone", "read_file");
            return new AgentLoop.Result(true, "你好", 1);
        });
        service.replyToMention(event(), agentId, "你好");
        verifyNoInteractions(tasks, sessions, commands);
        verify(messages).sendAsAgent(eq(groupId), eq(agentId), argThat(reply -> "你好".equals(reply.getContent().get("text"))));
    }

    @Test
    void modelPreparesOneWorkspaceAndCommandsUseThatSameDirectory() {
        when(commands.run(eq(workspaceId), eq("chosen"), anyList(), eq(Map.of())))
                .thenReturn(Map.of("ok", false, "exitCode", 1, "stderr", "build failed"));
        when(loop.run(anyString(), anyList(), any(), any())).thenAnswer(invocation -> {
            Supplier<List<ToolCallback>> available = invocation.getArgument(2);
            var prepare = find(available, "prepare_workspace");
            assertThat(prepare.call("{\"title\":\"实现\",\"requirement\":\"需求\"}")).contains(workspaceId.toString());
            prepare.call("{\"title\":\"继续\",\"requirement\":\"同一需求\"}");
            assertThat(find(available, "run_command").call("{\"command\":[\"git\",\"status\"],\"directory\":\"chosen\"}"))
                    .contains("build failed");
            return new AgentLoop.Result(true, "报告真实失败", 2);
        });
        service.replyToMention(event(), agentId, "实现功能");
        verify(tasks, times(1)).create(eq(projectId), eq(actor), any());
        verify(sessions, times(1)).acquire(taskId, projectId, workspaceId);
        verify(sessions).release(workspaceId, taskId);
    }

    @Test
    void useWorkspaceContinuesExistingTaskWithoutCreatingAnother() {
        var stored = new qg.qgent.task.entity.TaskEntity();
        stored.setId(taskId);
        stored.setProjectId(projectId);
        stored.setGroupId(groupId);
        stored.setWorkspaceId(workspaceId);
        when(taskMapper.selectById(taskId)).thenReturn(stored);
        when(tasks.response(stored)).thenReturn(task);
        when(loop.run(anyString(), anyList(), any(), any())).thenAnswer(invocation -> {
            Supplier<List<ToolCallback>> available = invocation.getArgument(2);
            assertThat(find(available, "use_workspace").call("{\"taskId\":\"" + taskId + "\"}"))
                    .contains(workspaceId.toString());
            return new AgentLoop.Result(true, "继续完成", 2);
        });
        service.replyToMention(event(), agentId, "继续工作");
        verify(tasks, never()).create(any(), any(), any());
        verify(sessions).acquire(taskId, projectId, workspaceId);
    }

    private ToolCallback find(Supplier<List<ToolCallback>> available, String name) {
        return available.get().stream().filter(t -> name.equals(t.getToolDefinition().name())).findFirst().orElseThrow();
    }

    private MessageSentEvent event() {
        return new MessageSentEvent(actor, projectId, groupId, UUID.randomUUID(), List.of());
    }
}

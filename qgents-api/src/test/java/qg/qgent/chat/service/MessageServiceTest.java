package qg.qgent.chat.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;

import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.attachment.service.AttachmentService;
import qg.qgent.chat.dto.MessageResponse;
import qg.qgent.chat.dto.MessageSendRequest;
import qg.qgent.chat.dto.Mention;
import qg.qgent.chat.event.MessageSentEvent;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.mapper.GroupAgentMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.common.web.ApiException;
import qg.qgent.notification.service.NotificationService;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.user.mapper.UserMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 群聊消息的发送、引用与 Agent 身份验证。
 */
class MessageServiceTest {

    @BeforeEach
    void initializeMetadataBeforeEachTest() {
        initializeMybatisMetadata();
    }

    private static void initializeMybatisMetadata() {
        if (TableInfoHelper.getTableInfo(ChatGroupEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "MessageServiceTest"),
                    ChatGroupEntity.class);
        }
        if (TableInfoHelper.getTableInfo(MessageEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "MessageServiceTest"),
                    MessageEntity.class);
        }
    }

    @Test
    void agentMessageRejectsMissingCrossTeamAndInactiveAgentBeforeWriting() {
        UUID projectId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(teamId);
        MessageMapper messages = mock(MessageMapper.class);
        ChatGroupMapper groups = mock(ChatGroupMapper.class);
        GroupAgentMapper groupAgents = mock(GroupAgentMapper.class);
        AgentMapper agents = mock(AgentMapper.class);
        ProjectMapper projects = mock(ProjectMapper.class);
        when(groups.selectOne(any())).thenReturn(group);
        when(projects.selectById(projectId)).thenReturn(project);
        MessageService service = new MessageService(messages, groups, groupAgents, mock(UserMapper.class), agents,
                projects, mock(ProjectAccessService.class), mock(GroupService.class),
                new ObjectMapper(), mock(EventService.class), mock(NotificationService.class), mock(AttachmentService.class),
                mock(ApplicationEventPublisher.class));
        MessageSendRequest request = new MessageSendRequest();
        request.setType("TEXT");
        request.setContent(Map.of("text", "任务更新"));

        ApiException missing = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.sendAsAgent(groupId, agentId, request));
        assertThat(missing.code()).isEqualTo("AGENT_NOT_FOUND");

        AgentEntity crossTeam = new AgentEntity();
        crossTeam.setTeamId(UUID.randomUUID());
        crossTeam.setStatus("ACTIVE");
        when(agents.selectById(agentId)).thenReturn(crossTeam);
        ApiException wrongTeam = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.sendAsAgent(groupId, agentId, request));
        assertThat(wrongTeam.code()).isEqualTo("AGENT_NOT_IN_PROJECT_TEAM");

        AgentEntity inactive = new AgentEntity();
        inactive.setTeamId(teamId);
        inactive.setStatus("INACTIVE");
        when(agents.selectById(agentId)).thenReturn(inactive);
        ApiException disabled = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.sendAsAgent(groupId, agentId, request));
        assertThat(disabled.code()).isEqualTo("AGENT_NOT_ACTIVE");
        verifyNoInteractions(messages, groupAgents);
    }

    @Test
    void activeAgentInProjectTeamCanSendAndJoinGroup() {
        UUID projectId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(teamId);
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setTeamId(teamId);
        agent.setName("编排助手");
        agent.setStatus("ACTIVE");
        MessageMapper messages = mock(MessageMapper.class);
        ChatGroupMapper groups = mock(ChatGroupMapper.class);
        GroupAgentMapper groupAgents = mock(GroupAgentMapper.class);
        AgentMapper agents = mock(AgentMapper.class);
        ProjectMapper projects = mock(ProjectMapper.class);
        when(groups.selectOne(any())).thenReturn(group);
        when(projects.selectById(projectId)).thenReturn(project);
        when(agents.selectById(agentId)).thenReturn(agent);
        when(messages.nextSequence(groupId)).thenReturn(1L);
        AtomicReference<MessageEntity> inserted = new AtomicReference<>();
        when(messages.insert(any(MessageEntity.class))).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });
        when(messages.selectById(any())).thenAnswer(invocation -> inserted.get());
        when(groupAgents.insertAgent(groupId, agentId)).thenReturn(1);
        MessageService service = new MessageService(messages, groups, groupAgents, mock(UserMapper.class), agents,
                projects, mock(ProjectAccessService.class), mock(GroupService.class),
                new ObjectMapper(), mock(EventService.class), mock(NotificationService.class), mock(AttachmentService.class),
                mock(ApplicationEventPublisher.class));
        MessageSendRequest request = new MessageSendRequest();
        request.setType("TEXT");
        request.setContent(Map.of("text", "任务更新"));

        MessageResponse response = service.sendAsAgent(groupId, agentId, request);

        assertThat(response.getSenderType()).isEqualTo("AGENT");
        verify(messages).insert(any(MessageEntity.class));
        verify(groupAgents).insertAgent(groupId, agentId);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void userMessageRetryDoesNotRepeatAgentOrUserMentionEvents(boolean originallyMentioned) {
        UUID projectId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        MessageMapper messages = mock(MessageMapper.class);
        ChatGroupMapper groups = mock(ChatGroupMapper.class);
        when(groups.selectOne(any())).thenReturn(group);
        when(messages.nextSequence(groupId)).thenReturn(1L);
        AtomicReference<MessageEntity> stored = new AtomicReference<>();
        when(messages.selectOne(any())).thenAnswer(invocation -> stored.get());
        when(messages.insert(any(MessageEntity.class))).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        });
        when(messages.selectById(any())).thenAnswer(invocation -> stored.get());
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        MessageService service = new MessageService(messages, groups, mock(GroupAgentMapper.class),
                mock(UserMapper.class), mock(AgentMapper.class), mock(ProjectMapper.class),
                mock(ProjectAccessService.class), mock(GroupService.class), new ObjectMapper(),
                mock(EventService.class), mock(NotificationService.class), mock(AttachmentService.class),
                publisher);
        MessageSendRequest request = new MessageSendRequest();
        request.setType("TEXT");
        request.setContent(Map.of("text", "@编排助手 补齐邮箱登录功能"));
        Mention agent = new Mention();
        agent.setType("AGENT");
        agent.setId(UUID.randomUUID());
        Mention user = new Mention();
        user.setType("USER");
        user.setId(UUID.randomUUID());
        request.setMentions(originallyMentioned ? List.of(agent, user) : List.of());
        request.setClientMessageId("message-" + UUID.randomUUID());

        MessageResponse first = service.send(actor, projectId, groupId, request);
        MessageResponse second = service.send(actor, projectId, groupId, request);
        request.setMentions(List.of(agent));
        MessageResponse changedRetry = service.send(actor, projectId, groupId, request);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(changedRetry.getId()).isEqualTo(first.getId());
        verify(messages, times(1)).insert(any(MessageEntity.class));
        if (!originallyMentioned) {
            verifyNoInteractions(publisher);
            return;
        }
        ArgumentCaptor<MessageSentEvent> event = ArgumentCaptor.forClass(MessageSentEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue().messageId()).isEqualTo(UUID.fromString(first.getId()));
        assertThat(event.getValue().mentions()).containsExactly(agent, user);
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void duplicateInsertReturnsExistingMessageWithoutMentionEvent() {
        UUID projectId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        MessageEntity existing = new MessageEntity();
        existing.setId(UUID.randomUUID());
        existing.setGroupId(groupId);
        existing.setSequenceNo(1L);
        existing.setMessageType("TEXT");
        existing.setContent("{\"text\":\"原消息\"}");
        MessageMapper messages = mock(MessageMapper.class);
        ChatGroupMapper groups = mock(ChatGroupMapper.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(groups.selectOne(any())).thenReturn(group);
        when(messages.selectOne(any())).thenReturn(null, existing);
        when(messages.nextSequence(groupId)).thenReturn(2L);
        when(messages.insert(any(MessageEntity.class))).thenThrow(new DuplicateKeyException("duplicate"));
        MessageService service = new MessageService(messages, groups, mock(GroupAgentMapper.class),
                mock(UserMapper.class), mock(AgentMapper.class), mock(ProjectMapper.class),
                mock(ProjectAccessService.class), mock(GroupService.class), new ObjectMapper(),
                mock(EventService.class), mock(NotificationService.class), mock(AttachmentService.class), publisher);
        MessageSendRequest request = new MessageSendRequest();
        request.setType("TEXT");
        request.setContent(Map.of("text", "重试"));
        request.setClientMessageId("duplicate-message");
        Mention agent = new Mention();
        agent.setType("AGENT");
        agent.setId(UUID.randomUUID());
        request.setMentions(List.of(agent));

        MessageResponse response = service.send(UUID.randomUUID(), projectId, groupId, request);

        assertThat(response.getId()).isEqualTo(existing.getId().toString());
        verifyNoInteractions(publisher);
    }

    @Test
    void replyStoresOnlyTextAndOriginalMessageId() {
        MessageMapper messages = mock(MessageMapper.class);
        ChatGroupMapper groups = mock(ChatGroupMapper.class);
        ChatGroupEntity group = new ChatGroupEntity();
        UUID groupId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        group.setId(groupId);
        group.setProjectId(UUID.randomUUID());
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setTeamId(teamId);
        agent.setName("编排助手");
        agent.setStatus("ACTIVE");
        ProjectEntity project = new ProjectEntity();
        project.setId(group.getProjectId());
        project.setTeamId(teamId);
        when(groups.selectOne(any())).thenReturn(group);
        when(messages.nextSequence(groupId)).thenReturn(1L);
        AtomicReference<MessageEntity> inserted = new AtomicReference<>();
        when(messages.insert(any(MessageEntity.class))).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });
        when(messages.selectById(any())).thenAnswer(invocation -> inserted.get());
        AgentMapper agents = mock(AgentMapper.class);
        when(agents.selectById(agentId)).thenReturn(agent);
        ProjectMapper projects = mock(ProjectMapper.class);
        when(projects.selectById(group.getProjectId())).thenReturn(project);
        MessageService service = new MessageService(messages, groups, mock(GroupAgentMapper.class),
                mock(UserMapper.class), agents, projects,
                mock(ProjectAccessService.class), mock(GroupService.class),
                new ObjectMapper(), mock(EventService.class), mock(NotificationService.class), mock(AttachmentService.class),
                mock(ApplicationEventPublisher.class));

        MessageSendRequest request = new MessageSendRequest();
        request.setType("TEXT");
        request.setContent(Map.of("text", "这里回复正文"));
        request.setReplyToId(UUID.randomUUID());
        MessageEntity quoted = new MessageEntity();
        quoted.setGroupId(groupId);
        when(messages.selectById(request.getReplyToId())).thenReturn(quoted);

        MessageResponse response = service.sendAsAgent(groupId, agentId, request);

        ArgumentCaptor<MessageEntity> captured = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messages).insert(captured.capture());
        String storedContent = captured.getValue().getContent();
        assertThat(storedContent).isEqualTo("{\"text\":\"这里回复正文\"}");
        assertThat(response.getType()).isEqualTo("TEXT");
        assertThat(response.getReplyToId()).isEqualTo(request.getReplyToId().toString());
        assertThat(response.getContent()).containsOnlyKeys("text");

    }

    @Test
    void plainJsonTextIsNotInterpretedAsAnExecutionEnvelope() {
        MessageMapper messages = mock(MessageMapper.class);
        ChatGroupMapper groups = mock(ChatGroupMapper.class);
        ChatGroupEntity group = new ChatGroupEntity();
        UUID groupId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        group.setId(groupId);
        group.setProjectId(UUID.randomUUID());
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setTeamId(teamId);
        agent.setName("编排助手");
        agent.setStatus("ACTIVE");
        ProjectEntity project = new ProjectEntity();
        project.setId(group.getProjectId());
        project.setTeamId(teamId);
        when(groups.selectOne(any())).thenReturn(group);
        when(messages.nextSequence(groupId)).thenReturn(1L);
        AtomicReference<MessageEntity> inserted = new AtomicReference<>();
        when(messages.insert(any(MessageEntity.class))).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });
        when(messages.selectById(any())).thenAnswer(invocation -> inserted.get());
        AgentMapper agents = mock(AgentMapper.class);
        when(agents.selectById(agentId)).thenReturn(agent);
        ProjectMapper projects = mock(ProjectMapper.class);
        when(projects.selectById(group.getProjectId())).thenReturn(project);
        MessageService service = new MessageService(messages, groups, mock(GroupAgentMapper.class),
                mock(UserMapper.class), agents, projects,
                mock(ProjectAccessService.class), mock(GroupService.class),
                new ObjectMapper(), mock(EventService.class), mock(NotificationService.class), mock(AttachmentService.class),
                mock(ApplicationEventPublisher.class));

        MessageSendRequest request = new MessageSendRequest();
        request.setType("TEXT");
        request.setContent(Map.of("text", "{\"quotedMessageId\":\"example\",\"quotedText\":\"text\",\"replyText\":\"reply\"}"));

        MessageResponse response = service.sendAsAgent(groupId, agentId, request);

        assertThat(response.getContent()).isEqualTo(request.getContent());
        assertThat(response.getReplyToId()).isNull();
    }

    @Test
    void incrementalMessagesAreReadAfterCursorInAscendingOrder() {
        UUID projectId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        MessageMapper messages = mock(MessageMapper.class);
        ChatGroupMapper groups = mock(ChatGroupMapper.class);
        GroupService groupService = mock(GroupService.class);
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        when(groups.selectById(groupId)).thenReturn(group);
        MessageEntity first = message(groupId, 11L, "第一条");
        MessageEntity second = message(groupId, 12L, "第二条");
        when(messages.selectAfterSequence(groupId, 10L, 3)).thenReturn(List.of(first, second));

        MessageService service = new MessageService(messages, groups, mock(GroupAgentMapper.class),
                mock(UserMapper.class), mock(AgentMapper.class), mock(ProjectMapper.class),
                mock(ProjectAccessService.class), groupService,
                new ObjectMapper(), mock(EventService.class), mock(NotificationService.class), mock(AttachmentService.class),
                mock(ApplicationEventPublisher.class));

        var page = service.listAfterSequence(actor, projectId, groupId, 10L, 2);

        assertThat(page.getData()).extracting(MessageResponse::getSequence).containsExactly(11L, 12L);
        assertThat(page.getPage().isHasMore()).isFalse();
        verify(groupService).requireGroupMember(projectId, groupId, actor);
        verify(messages).selectAfterSequence(groupId, 10L, 3);
    }

    @Test
    void incrementalMessagesRejectNegativeCursorBeforeQuery() {
        UUID projectId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        MessageMapper messages = mock(MessageMapper.class);
        ChatGroupMapper groups = mock(ChatGroupMapper.class);
        when(groups.selectById(groupId)).thenReturn(group);
        MessageService service = new MessageService(messages, groups, mock(GroupAgentMapper.class),
                mock(UserMapper.class), mock(AgentMapper.class), mock(ProjectMapper.class),
                mock(ProjectAccessService.class), mock(GroupService.class),
                new ObjectMapper(), mock(EventService.class), mock(NotificationService.class), mock(AttachmentService.class),
                mock(ApplicationEventPublisher.class));

        assertThatThrownBy(() -> service.listAfterSequence(UUID.randomUUID(), projectId, groupId, -1, 100))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_MESSAGE_CURSOR"));
        verify(messages, never()).selectAfterSequence(any(), anyLong(), anyInt());
    }

    private MessageEntity message(UUID groupId, long sequence, String text) {
        MessageEntity entity = new MessageEntity();
        entity.setId(UUID.randomUUID());
        entity.setGroupId(groupId);
        entity.setSequenceNo(sequence);
        entity.setMessageType("TEXT");
        entity.setContent("{\"text\":\"" + text + "\"}");
        entity.setMentions("[]");
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }
}

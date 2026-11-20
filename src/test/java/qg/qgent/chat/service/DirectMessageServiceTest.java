package qg.qgent.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.agent.service.AgentService;
import qg.qgent.chat.dto.DirectConversationCreateRequest;
import qg.qgent.chat.dto.DirectConversationResponse;
import qg.qgent.chat.dto.MessageSendRequest;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.entity.DirectConversationEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.DirectConversationMapper;
import qg.qgent.chat.mapper.GroupUserMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.mapper.view.GroupLatestMessageRow;
import qg.qgent.chat.mapper.view.GroupUnreadRow;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.support.TestIds;
import qg.qgent.user.entity.UserEntity;
import qg.qgent.user.mapper.UserMapper;

/**
 * 私聊的访问边界、会话唯一性与对外字段：只有参与者能读写，管理员没有越权入口。
 */
class DirectMessageServiceTest {

    private final DirectConversationMapper conversations = mock(DirectConversationMapper.class);
    private final ChatGroupMapper groups = mock(ChatGroupMapper.class);
    private final GroupUserMapper groupUsers = mock(GroupUserMapper.class);
    private final MessageMapper messages = mock(MessageMapper.class);
    private final MessageService messageService = mock(MessageService.class);
    private final GroupService groupService = mock(GroupService.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final ProjectMemberMapper projectMembers = mock(ProjectMemberMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final AgentService agents = mock(AgentService.class);
    private final AgentMapper agentRows = mock(AgentMapper.class);
    private final UserMapper users = mock(UserMapper.class);

    private final DirectMessageService service =
            new DirectMessageService(
                    conversations,
                    groups,
                    groupUsers,
                    messages,
                    messageService,
                    groupService,
                    access,
                    projectMembers,
                    projects,
                    agents,
                    agentRows,
                    users);

    private final Long projectId = TestIds.next();
    private final Long actor = TestIds.next();
    private final Long conversationId = TestIds.next();

    @BeforeEach
    void emptyUnreadAndLatest() {
        when(messages.selectLatestByGroupIds(any())).thenReturn(List.of());
        when(messages.countUnreadByGroupIds(any(), any())).thenReturn(List.of());
    }

    @Test
    void projectAdminWhoIsNotAParticipantCannotReadAConversation() {
        when(conversations.selectById(conversationId))
                .thenReturn(
                        conversation(
                                conversationId,
                                TestIds.next(),
                                DirectConversationEntity.TYPE_USER,
                                TestIds.next(),
                                DirectConversationEntity.TYPE_USER,
                                TestIds.next()));

        ApiException denied =
                catchThrowableOfType(
                        () -> service.get(actor, projectId, conversationId), ApiException.class);

        assertThat(denied.code()).isEqualTo("DIRECT_CONVERSATION_NOT_FOUND");
        assertThat(denied.status()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(groups, never()).selectById(any());
    }

    @Test
    void nonParticipantCannotSendIntoTheConversation() {
        when(conversations.selectById(conversationId))
                .thenReturn(
                        conversation(
                                conversationId,
                                TestIds.next(),
                                DirectConversationEntity.TYPE_USER,
                                TestIds.next(),
                                DirectConversationEntity.TYPE_USER,
                                TestIds.next()));

        ApiException denied =
                catchThrowableOfType(
                        () -> service.send(actor, projectId, conversationId, text("你好")),
                        ApiException.class);

        assertThat(denied.code()).isEqualTo("DIRECT_CONVERSATION_NOT_FOUND");
        verify(messageService, never()).sendInConversation(any(), any(), any());
    }

    @Test
    void startingTheSameConversationTwiceReturnsTheExistingOne() {
        Long peer = TestIds.next();
        DirectConversationEntity existing =
                conversation(
                        conversationId,
                        projectId,
                        DirectConversationEntity.TYPE_USER,
                        Math.min(actor, peer),
                        DirectConversationEntity.TYPE_USER,
                        Math.max(actor, peer));
        when(conversations.selectOne(any())).thenReturn(existing);
        when(conversations.selectById(conversationId)).thenReturn(existing);
        when(groups.selectById(conversationId)).thenReturn(directGroup(conversationId, projectId));
        when(projectMembers.selectByProjectAndUser(projectId, peer))
                .thenReturn(mock(qg.qgent.project.entity.ProjectMemberEntity.class));
        when(users.selectBatchIds(any()))
                .thenReturn(List.of(user(Math.max(actor, peer), "小王")));

        DirectConversationResponse response =
                service.create(actor, projectId, request("USER", peer));

        assertThat(response.getId()).isEqualTo(conversationId.toString());
        assertThat(response.getPeer().id()).isEqualTo(Long.toString(Math.max(actor, peer)));
        verify(groups, never()).insert(any(ChatGroupEntity.class));
        verify(conversations, never()).insert(any(DirectConversationEntity.class));
        verify(projects).selectByIdForUpdate(projectId);
    }

    @Test
    void userAgentConversationRegistersOnlyTheUserAsGroupMember() {
        Long agentId = TestIds.next();
        AtomicReference<ChatGroupEntity> storedGroup = new AtomicReference<>();
        AtomicReference<DirectConversationEntity> storedConversation = new AtomicReference<>();
        when(groups.insert(any(ChatGroupEntity.class)))
                .thenAnswer(
                        call -> {
                            ChatGroupEntity group = call.getArgument(0);
                            group.setId(conversationId);
                            storedGroup.set(group);
                            return 1;
                        });
        when(conversations.insert(any(DirectConversationEntity.class)))
                .thenAnswer(
                        call -> {
                            storedConversation.set(call.getArgument(0));
                            return 1;
                        });
        when(conversations.selectOne(any())).thenReturn(null);
        when(conversations.selectById(any())).thenAnswer(call -> storedConversation.get());
        when(groups.selectById(any())).thenAnswer(call -> storedGroup.get());
        when(agents.requireActiveProjectAgent(projectId, agentId)).thenReturn(activeAgent(agentId));
        when(agentRows.selectBatchIds(any()))
                .thenReturn(List.of(activeAgent(agentId)));

        DirectConversationResponse response =
                service.create(actor, projectId, request("AGENT", agentId));

        assertThat(storedGroup.get().getKind()).isEqualTo(ChatGroupEntity.KIND_DIRECT);
        assertThat(storedGroup.get().getDefaultGroup()).isFalse();
        assertThat(storedConversation.get().getParticipantAType())
                .isEqualTo(DirectConversationEntity.TYPE_USER);
        assertThat(storedConversation.get().getParticipantAId()).isEqualTo(actor);
        assertThat(storedConversation.get().getParticipantBType())
                .isEqualTo(DirectConversationEntity.TYPE_AGENT);
        assertThat(storedConversation.get().getParticipantBId()).isEqualTo(agentId);
        verify(groupUsers).insertUser(conversationId, actor);
        verify(groupUsers, never()).insertUser(any(), eq(agentId));
        assertThat(response.getPeer().type()).isEqualTo("AGENT");
        assertThat(response.getPeer().displayName()).isEqualTo("评审助手");
    }

    @Test
    void userUserConversationStoresThePairInCanonicalOrder() {
        Long peer = actor + 10;
        AtomicReference<DirectConversationEntity> stored = new AtomicReference<>();
        when(groups.insert(any(ChatGroupEntity.class)))
                .thenAnswer(
                        call -> {
                            call.getArgument(0, ChatGroupEntity.class).setId(conversationId);
                            return 1;
                        });
        when(conversations.insert(any(DirectConversationEntity.class)))
                .thenAnswer(
                        call -> {
                            stored.set(call.getArgument(0));
                            return 1;
                        });
        when(conversations.selectOne(any())).thenReturn(null);
        when(conversations.selectById(any())).thenAnswer(call -> stored.get());
        when(groups.selectById(any())).thenReturn(directGroup(conversationId, projectId));
        when(projectMembers.selectByProjectAndUser(projectId, peer))
                .thenReturn(mock(qg.qgent.project.entity.ProjectMemberEntity.class));
        when(users.selectBatchIds(any())).thenReturn(List.of(user(peer, "小王")));

        service.create(actor, projectId, request("USER", peer));

        assertThat(stored.get().getParticipantAId()).isEqualTo(actor);
        assertThat(stored.get().getParticipantBId()).isEqualTo(peer);
        verify(groupUsers).insertUser(conversationId, peer);
    }

    @Test
    void peerMustBeAnotherProjectMemberAndTheConversationIsNotCreatedForInvalidPeers() {
        ApiException withSelf =
                catchThrowableOfType(
                        () -> service.create(actor, projectId, request("USER", actor)),
                        ApiException.class);
        ApiException withOutsider =
                catchThrowableOfType(
                        () -> service.create(actor, projectId, request("USER", TestIds.next())),
                        ApiException.class);

        assertThat(withSelf.code()).isEqualTo("DIRECT_PEER_INVALID");
        assertThat(withOutsider.code()).isEqualTo("DIRECT_PEER_INVALID");
        assertThat(withOutsider.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        verify(groups, never()).insert(any(ChatGroupEntity.class));
        verify(projects, never()).selectByIdForUpdate(any());
    }

    @Test
    void listSortsByLatestActivityAndCarriesPeerLastMessageAndUnreadCount() {
        Long otherProjectConversation = TestIds.next();
        Long peerAgent = TestIds.next();
        Long peerUser = TestIds.next();
        DirectConversationEntity withAgent =
                conversation(
                        conversationId,
                        projectId,
                        DirectConversationEntity.TYPE_USER,
                        actor,
                        DirectConversationEntity.TYPE_AGENT,
                        peerAgent);
        DirectConversationEntity withUser =
                conversation(
                        otherProjectConversation,
                        projectId,
                        DirectConversationEntity.TYPE_USER,
                        peerUser,
                        DirectConversationEntity.TYPE_USER,
                        actor);
        when(conversations.listForUser(projectId, actor))
                .thenReturn(List.of(withUser, withAgent));
        ChatGroupEntity older = directGroup(otherProjectConversation, projectId);
        older.setCreatedAt(LocalDateTime.now().minusDays(2));
        ChatGroupEntity newer = directGroup(conversationId, projectId);
        newer.setCreatedAt(LocalDateTime.now().minusDays(1));
        newer.setLastMessageAt(LocalDateTime.now());
        when(groups.selectBatchIds(any())).thenReturn(List.of(older, newer));
        when(agentRows.selectBatchIds(any())).thenReturn(List.of(activeAgent(peerAgent)));
        when(users.selectBatchIds(any())).thenReturn(List.of(user(peerUser, "小王")));
        GroupLatestMessageRow latest = new GroupLatestMessageRow();
        latest.setGroupId(conversationId);
        latest.setSenderName("评审助手");
        latest.setText("已改完");
        latest.setMessageType("TEXT");
        when(messages.selectLatestByGroupIds(any())).thenReturn(List.of(latest));
        GroupUnreadRow unread = new GroupUnreadRow();
        unread.setGroupId(conversationId);
        unread.setUnread(2L);
        when(messages.countUnreadByGroupIds(any(), any())).thenReturn(List.of(unread));

        List<DirectConversationResponse> found = service.list(actor, projectId);

        assertThat(found).hasSize(2);
        assertThat(found.getFirst().getId()).isEqualTo(conversationId.toString());
        assertThat(found.getFirst().getPeer().displayName()).isEqualTo("评审助手");
        assertThat(found.getFirst().getLastMessage().getText()).isEqualTo("已改完");
        assertThat(found.getFirst().getUnreadCount()).isEqualTo(2L);
        assertThat(found.get(1).getUnreadCount()).isZero();
        assertThat(found.get(1).getLastMessage()).isNull();
    }

    private DirectConversationCreateRequest request(String peerType, Long peerId) {
        DirectConversationCreateRequest request = new DirectConversationCreateRequest();
        request.setPeerType(peerType);
        request.setPeerId(peerId);
        return request;
    }

    private MessageSendRequest text(String content) {
        MessageSendRequest request = new MessageSendRequest();
        request.setType("TEXT");
        request.setContent(java.util.Map.of("text", content));
        return request;
    }

    private DirectConversationEntity conversation(
        Long groupId, Long project, String aType, Long aId, String bType, Long bId) {
        DirectConversationEntity conversation = new DirectConversationEntity();
        conversation.setGroupId(groupId);
        conversation.setProjectId(project);
        conversation.setParticipantAType(aType);
        conversation.setParticipantAId(aId);
        conversation.setParticipantBType(bType);
        conversation.setParticipantBId(bId);
        conversation.setCreatedAt(LocalDateTime.now());
        return conversation;
    }

    private ChatGroupEntity directGroup(Long groupId, Long project) {
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(project);
        group.setKind(ChatGroupEntity.KIND_DIRECT);
        group.setDefaultGroup(false);
        group.setName("私聊");
        group.setCreatedAt(LocalDateTime.now());
        return group;
    }

    private AgentEntity activeAgent(Long agentId) {
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setProjectId(projectId);
        agent.setName("评审助手");
        agent.setStatus(AgentEntity.STATUS_ACTIVE);
        return agent;
    }

    private UserEntity user(Long userId, String displayName) {
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setDisplayName(displayName);
        return user;
    }
}

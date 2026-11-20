package qg.qgent.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.attachment.service.AttachmentService;
import qg.qgent.chat.dto.Mention;
import qg.qgent.chat.dto.MessageSendRequest;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.entity.DirectConversationEntity;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.event.DirectMessageSentEvent;
import qg.qgent.chat.event.GroupMessageCreatedEvent;
import qg.qgent.chat.event.MessageSentEvent;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.DirectConversationMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.common.web.ApiException;
import qg.qgent.notification.service.NotificationService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.support.TestIds;
import qg.qgent.user.mapper.UserMapper;

/**
 * 私聊消息：直接发给 Agent 即触发回复，且私聊不外溢成群记忆或提及通知。
 */
class MessageServiceDirectTest {

    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final ChatGroupMapper groupMapper = mock(ChatGroupMapper.class);
    private final DirectConversationMapper directConversations =
            mock(DirectConversationMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final GroupService groupService = mock(GroupService.class);
    private final EventService eventService = mock(EventService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final ApplicationEventPublisher eventPublisher =
            mock(ApplicationEventPublisher.class);

    private final MessageService service =
            new MessageService(
                    messageMapper,
                    groupMapper,
                    directConversations,
                    userMapper,
                    groupService,
                    new ObjectMapper(),
                    eventService,
                    notificationService,
                    new MessageViews(
                            userMapper,
                            mock(AgentMapper.class),
                            mock(AttachmentService.class),
                            new ObjectMapper()),
                    eventPublisher);

    private final Long projectId = TestIds.next();
    private final Long conversationId = TestIds.next();
    private final Long actor = TestIds.next();
    private final Long agentId = TestIds.next();

    @BeforeEach
    void initializeMetadata() {
        initialize(ChatGroupEntity.class);
        initialize(MessageEntity.class);
    }

    private static void initialize(Class<?> entity) {
        if (TableInfoHelper.getTableInfo(entity) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), "MessageServiceDirectTest"),
                    entity);
        }
    }

    @Test
    void userMessageInAnAgentConversationTriggersThatAgentWithoutMentionsOrGroupMemory() {
        ChatGroupEntity conversation = directConversation();
        givenConversation(conversation);
        when(directConversations.selectById(conversationId))
                .thenReturn(conversationRow(agentId));
        when(messageMapper.nextSequence(conversationId)).thenReturn(1L);

        service.sendInConversation(actor, conversation, text("帮我看下这个报错"));

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(events.capture());
        assertThat(events.getValue())
                .isInstanceOfSatisfying(
                        DirectMessageSentEvent.class,
                        event -> {
                            assertThat(event.actor()).isEqualTo(actor);
                            assertThat(event.conversationId()).isEqualTo(conversationId);
                            assertThat(event.agentId()).isEqualTo(agentId);
                        });
        verify(eventPublisher, never()).publishEvent(any(GroupMessageCreatedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(MessageSentEvent.class));
        verify(eventService)
                .publish(
                        eq(projectId),
                        eq(conversationId),
                        eq("message.created"),
                        any(),
                        any());
    }

    @Test
    void idempotentRetryReturnsTheStoredMessageWithoutTriggeringTheAgentAgain() {
        ChatGroupEntity conversation = directConversation();
        givenConversation(conversation);
        MessageSendRequest body = text("重试这一条");
        body.setClientMessageId("client-1");
        MessageEntity stored = new MessageEntity();
        stored.setId(TestIds.next());
        stored.setGroupId(conversationId);
        stored.setSequenceNo(3L);
        stored.setAuthorUserId(actor);
        stored.setSenderType("USER");
        stored.setMessageType("TEXT");
        stored.setContent("{\"text\":\"重试这一条\"}");
        stored.setClientMessageId("client-1");
        stored.setCreatedAt(java.time.LocalDateTime.now());
        when(messageMapper.selectOne(any())).thenReturn(stored);

        service.sendInConversation(actor, conversation, body);

        verify(messageMapper, never()).insert(any(MessageEntity.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void directMessageRejectsMentionsInsteadOfLeakingThemIntoNotifications() {
        ChatGroupEntity conversation = directConversation();
        givenConversation(conversation);
        MessageSendRequest body = text("你好");
        Mention mention = new Mention();
        mention.setType("USER");
        mention.setId(TestIds.next());
        body.setMentions(List.of(mention));

        ApiException denied =
                catchThrowableOfType(
                        () -> service.sendInConversation(actor, conversation, body),
                        ApiException.class);

        assertThat(denied.code()).isEqualTo("DIRECT_MENTION_NOT_SUPPORTED");
        assertThat(denied.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        verify(messageMapper, never()).insert(any(MessageEntity.class));
    }

    @Test
    void anAgentCanOnlyReplyInTheConversationWhereItIsThePeer() {
        ChatGroupEntity conversation = directConversation();
        when(groupMapper.selectOne(any())).thenReturn(conversation);
        when(directConversations.countAgentPeer(conversationId, agentId)).thenReturn(0);

        ApiException denied =
                catchThrowableOfType(
                        () -> service.replyInConversation(agentId, conversationId, text("回复")),
                        ApiException.class);

        assertThat(denied.code()).isEqualTo("AGENT_NOT_IN_CONVERSATION");
        verify(messageMapper, never()).insert(any(MessageEntity.class));
    }

    @Test
    void thePeerAgentRepliesInsideTheConversationAsAnAgentMessage() {
        ChatGroupEntity conversation = directConversation();
        givenConversation(conversation);
        when(directConversations.countAgentPeer(conversationId, agentId)).thenReturn(1);
        AtomicReference<MessageEntity> inserted = new AtomicReference<>();
        when(messageMapper.insert(any(MessageEntity.class)))
                .thenAnswer(
                        call -> {
                            MessageEntity message = call.getArgument(0);
                            message.setId(TestIds.next());
                            inserted.set(message);
                            return 1;
                        });

        service.replyInConversation(agentId, conversationId, text("已经修好了"));

        assertThat(inserted.get().getSenderType()).isEqualTo("AGENT");
        assertThat(inserted.get().getSenderAgentId()).isEqualTo(agentId);
        assertThat(inserted.get().getAuthorUserId()).isNull();
        // Agent 回复不触发新的运行，也不产生群记忆活跃状态。
        verify(eventPublisher, never()).publishEvent(any(DirectMessageSentEvent.class));
        verify(eventPublisher, never()).publishEvent(any(GroupMessageCreatedEvent.class));
    }

    private void givenConversation(ChatGroupEntity conversation) {
        when(groupMapper.selectOne(any())).thenReturn(conversation);
        when(messageMapper.nextSequence(conversationId)).thenReturn(1L);
        when(messageMapper.insert(any(MessageEntity.class)))
                .thenAnswer(
                        call -> {
                            MessageEntity message = call.getArgument(0);
                            message.setId(TestIds.next());
                            return 1;
                        });
        when(directConversations.countAgentPeer(conversationId, agentId)).thenReturn(1);
    }

    private ChatGroupEntity directConversation() {
        ChatGroupEntity conversation = new ChatGroupEntity();
        conversation.setId(conversationId);
        conversation.setProjectId(projectId);
        conversation.setKind(ChatGroupEntity.KIND_DIRECT);
        conversation.setDefaultGroup(false);
        conversation.setName("私聊");
        return conversation;
    }

    private DirectConversationEntity conversationRow(Long peerAgentId) {
        DirectConversationEntity row = new DirectConversationEntity();
        row.setGroupId(conversationId);
        row.setProjectId(projectId);
        row.setParticipantAType(DirectConversationEntity.TYPE_USER);
        row.setParticipantAId(actor);
        row.setParticipantBType(DirectConversationEntity.TYPE_AGENT);
        row.setParticipantBId(peerAgentId);
        return row;
    }

    private MessageSendRequest text(String content) {
        MessageSendRequest request = new MessageSendRequest();
        request.setType("TEXT");
        request.setContent(Map.of("text", content));
        return request;
    }
}

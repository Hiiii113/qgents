package qg.qgent.chat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.agent.service.AgentService;
import qg.qgent.chat.dto.DirectConversationCreateRequest;
import qg.qgent.chat.dto.DirectConversationResponse;
import qg.qgent.chat.dto.DirectPeer;
import qg.qgent.chat.dto.DirectReadResponse;
import qg.qgent.chat.dto.GroupLatestMessage;
import qg.qgent.chat.dto.GroupReadResponse;
import qg.qgent.chat.dto.MessageResponse;
import qg.qgent.chat.dto.MessageSendRequest;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.entity.DirectConversationEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.DirectConversationMapper;
import qg.qgent.chat.mapper.GroupUserMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.mapper.view.GroupLatestMessageRow;
import qg.qgent.chat.mapper.view.GroupUnreadRow;
import qg.qgent.common.dto.PageSlice;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.user.entity.UserEntity;
import qg.qgent.user.mapper.UserMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 私聊：同一项目内两位用户或一位用户与一个 Agent 的一对一会话。
 * <p>
 * 会话与群聊共用 chat_groups/messages 行（会话 ID 就是 chat_groups.id），因此序号、幂等、
 * 分页、已读和 Agent 运行记录都不必重写；参与者记录在 direct_conversations，只有双方能读写。
 */
@Service
@RequiredArgsConstructor
public class DirectMessageService {
    private static final String CONVERSATION_NAME = "私聊";

    private final DirectConversationMapper conversations;
    private final ChatGroupMapper groups;
    private final GroupUserMapper groupUsers;
    private final MessageMapper messages;
    private final MessageService messageService;
    private final GroupService groupService;
    private final ProjectAccessService access;
    private final ProjectMemberMapper projectMembers;
    private final ProjectMapper projects;
    private final AgentService agents;
    private final AgentMapper agentRows;
    private final UserMapper users;

    /**
     * 当前用户在项目里的私聊，按最近活跃时间倒序。
     */
    public List<DirectConversationResponse> list(Long actor, Long projectId) {
        access.requireProjectMember(projectId, actor);
        List<DirectConversationEntity> found = conversations.listForUser(projectId, actor);
        if (found.isEmpty()) {
            return List.of();
        }
        List<Long> ids = found.stream().map(DirectConversationEntity::getGroupId).toList();
        Map<Long, ChatGroupEntity> groupsById =
            groups.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(ChatGroupEntity::getId, Function.identity()));
        Map<Long, GroupLatestMessageRow> latestById =
            messages.selectLatestByGroupIds(ids).stream()
                .collect(Collectors.toMap(GroupLatestMessageRow::getGroupId, Function.identity()));
        Map<Long, Long> unreadById =
            messages.countUnreadByGroupIds(ids, actor).stream()
                .collect(Collectors.toMap(row -> row.getGroupId(), row -> row.getUnread()));
        Map<Long, DirectPeer> peers = peers(found, actor);
        return found.stream()
            .sorted(
                Comparator.comparing(
                        (DirectConversationEntity row) -> activity(groupOf(groupsById, row.getGroupId())))
                    .reversed())
            .map(row -> {
                ChatGroupEntity group = groupOf(groupsById, row.getGroupId());
                return response(
                    row,
                    group,
                    peers.get(row.getGroupId()),
                    latestById.get(row.getGroupId()),
                    unreadById.getOrDefault(row.getGroupId(), 0L));
            })
            .toList();
    }

    public DirectConversationResponse get(Long actor, Long projectId, Long conversationId) {
        Conversation conversation = requireParticipant(actor, projectId, conversationId);
        return response(
            conversation.row(),
            conversation.group(),
            peers(List.of(conversation.row()), actor).get(conversationId),
            messages.selectLatestByGroupIds(List.of(conversationId)).stream().findFirst().orElse(null),
            unreadCount(actor, conversationId));
    }

    /**
     * 发起或取回与同一对端的会话：同一项目同一对端只有一条会话，重复调用不会新建。
     */
    @Transactional
    public DirectConversationResponse create(
        Long actor, Long projectId, DirectConversationCreateRequest body) {
        access.requireProjectMember(projectId, actor);
        DirectConversationEntity.Peer peer = requirePeer(projectId, actor, body);
        Pair pair = canonical(actor, peer);
        // 项目行锁串行化同一项目的并发发起，避免建出两条会话或一条没有参与者的会话行。
        projects.selectByIdForUpdate(projectId);
        DirectConversationEntity existing = find(projectId, pair);
        if (existing != null) {
            return get(actor, projectId, existing.getGroupId());
        }
        return get(actor, projectId, persist(projectId, actor, pair).getGroupId());
    }

    public PageSlice<MessageResponse> messages(
        Long actor, Long projectId, Long conversationId, String cursor, int limit) {
        ChatGroupEntity conversation = requireParticipant(actor, projectId, conversationId).group();
        return messageService.listConversation(conversation, cursor, limit);
    }

    public PageSlice<MessageResponse> messagesAfter(
        Long actor, Long projectId, Long conversationId, long afterSequence, int limit) {
        ChatGroupEntity conversation = requireParticipant(actor, projectId, conversationId).group();
        return messageService.listConversationAfterSequence(conversation, afterSequence, limit);
    }

    public MessageResponse message(
        Long actor, Long projectId, Long conversationId, Long messageId) {
        ChatGroupEntity conversation = requireParticipant(actor, projectId, conversationId).group();
        return messageService.getConversationMessage(conversation, messageId);
    }

    /**
     * 发送私聊消息；对端是 Agent 时消息提交后自动触发它的回复，不需要提及。
     * 已停用的 Agent 不能继续聊天，但会话与历史消息仍可读取。
     */
    @Transactional
    public MessageResponse send(
        Long actor, Long projectId, Long conversationId, MessageSendRequest body) {
        Conversation conversation = requireParticipant(actor, projectId, conversationId);
        Long agentPeer = conversation.row().agentPeerId();
        if (agentPeer != null) {
            agents.requireActiveProjectAgent(projectId, agentPeer);
        }
        return messageService.sendInConversation(actor, conversation.group(), body);
    }

    @Transactional
    public DirectReadResponse markRead(Long actor, Long projectId, Long conversationId) {
        requireParticipant(actor, projectId, conversationId);
        GroupReadResponse read = groupService.advanceRead(actor, conversationId);
        return new DirectReadResponse(
            conversationId.toString(), read.getLastReadSequenceNo(), read.getUnreadCount());
    }

    /**
     * 参与者校验并取会话行；非参与者（包括项目管理员）一律按会话不存在处理。
     */
    private Conversation requireParticipant(Long actor, Long projectId, Long conversationId) {
        access.requireProjectMember(projectId, actor);
        DirectConversationEntity row = conversations.selectById(conversationId);
        if (row == null || !projectId.equals(row.getProjectId()) || !row.involves(actor)) {
            throw notFound();
        }
        ChatGroupEntity group = groups.selectById(conversationId);
        if (group == null || !group.isDirect() || !projectId.equals(group.getProjectId())) {
            throw new IllegalStateException("私聊会话行不一致, conversationId=" + conversationId);
        }
        return new Conversation(row, group);
    }

    /**
     * 对端必须是本项目成员或已启用的本项目 Agent；只有人与人会话才拒绝自己
     * （用户与 Agent 的自增 ID 各自独立，重号不代表是同一个人）。
     */
    private DirectConversationEntity.Peer requirePeer(
        Long projectId, Long actor, DirectConversationCreateRequest body) {
        String type = body.getPeerType().trim().toUpperCase(Locale.ROOT);
        Long peerId = body.getPeerId();
        if (DirectConversationEntity.TYPE_AGENT.equals(type)) {
            agents.requireActiveProjectAgent(projectId, peerId);
            return new DirectConversationEntity.Peer(DirectConversationEntity.TYPE_AGENT, peerId);
        }
        if (!DirectConversationEntity.TYPE_USER.equals(type)) {
            throw invalidPeer("对端必须是本项目成员或已启用的 Agent");
        }
        if (peerId.equals(actor)) {
            throw invalidPeer("不能和自己私聊");
        }
        if (projectMembers.selectByProjectAndUser(projectId, peerId) == null) {
            throw invalidPeer("对端必须是本项目成员或已启用的 Agent");
        }
        return new DirectConversationEntity.Peer(DirectConversationEntity.TYPE_USER, peerId);
    }

    /**
     * 参与者按类型（USER 在前）与 ID 升序规范化存储，唯一键据此保证同一对端只有一条会话。
     */
    private Pair canonical(Long actor, DirectConversationEntity.Peer peer) {
        return DirectConversationEntity.TYPE_USER.equals(peer.type())
            ? new Pair(
                DirectConversationEntity.TYPE_USER,
                Math.min(actor, peer.id()),
                DirectConversationEntity.TYPE_USER,
                Math.max(actor, peer.id()))
            : new Pair(
                DirectConversationEntity.TYPE_USER,
                actor,
                DirectConversationEntity.TYPE_AGENT,
                peer.id());
    }

    private DirectConversationEntity find(Long projectId, Pair pair) {
        return conversations.selectOne(
            Wrappers.<DirectConversationEntity>lambdaQuery()
                .eq(DirectConversationEntity::getProjectId, projectId)
                .eq(DirectConversationEntity::getParticipantAType, pair.aType())
                .eq(DirectConversationEntity::getParticipantAId, pair.aId())
                .eq(DirectConversationEntity::getParticipantBType, pair.bType())
                .eq(DirectConversationEntity::getParticipantBId, pair.bId()));
    }

    private DirectConversationEntity persist(Long projectId, Long actor, Pair pair) {
        ChatGroupEntity group = new ChatGroupEntity();
        group.setProjectId(projectId);
        group.setCreatedBy(actor);
        group.setDefaultGroup(false);
        group.setKind(ChatGroupEntity.KIND_DIRECT);
        group.setName(CONVERSATION_NAME);
        group.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        groups.insert(group);
        // 私聊的群成员就是用户参与者：消息可见范围、实时推送和已读游标都沿用这一关系。
        groupUsers.insertUser(group.getId(), pair.aId());
        if (DirectConversationEntity.TYPE_USER.equals(pair.bType())) {
            groupUsers.insertUser(group.getId(), pair.bId());
        }
        DirectConversationEntity conversation = new DirectConversationEntity();
        conversation.setGroupId(group.getId());
        conversation.setProjectId(projectId);
        conversation.setParticipantAType(pair.aType());
        conversation.setParticipantAId(pair.aId());
        conversation.setParticipantBType(pair.bType());
        conversation.setParticipantBId(pair.bId());
        conversation.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        conversations.insert(conversation);
        return conversation;
    }

    /**
     * 会话里每个人的对端一次查清，避免列表逐条查询用户或 Agent。
     */
    private Map<Long, DirectPeer> peers(List<DirectConversationEntity> found, Long actor) {
        Map<Long, DirectConversationEntity.Peer> peerOf = new HashMap<>();
        Set<Long> userIds = new HashSet<>();
        Set<Long> agentIds = new HashSet<>();
        for (DirectConversationEntity row : found) {
            DirectConversationEntity.Peer peer = row.peerOf(actor);
            peerOf.put(row.getGroupId(), peer);
            if (DirectConversationEntity.TYPE_AGENT.equals(peer.type())) {
                agentIds.add(peer.id());
            } else {
                userIds.add(peer.id());
            }
        }
        Map<Long, UserEntity> userRows =
            userIds.isEmpty()
                ? Map.of()
                : users.selectBatchIds(userIds).stream()
                    .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        Map<Long, AgentEntity> agentMap =
            agentIds.isEmpty()
                ? Map.of()
                : agentRows.selectBatchIds(agentIds).stream()
                    .collect(Collectors.toMap(AgentEntity::getId, Function.identity()));
        Map<Long, DirectPeer> result = new HashMap<>();
        peerOf.forEach(
            (conversationId, peer) ->
                result.put(conversationId, toPeer(peer, userRows, agentMap)));
        return result;
    }

    private DirectPeer toPeer(
        DirectConversationEntity.Peer peer,
        Map<Long, UserEntity> userRows,
        Map<Long, AgentEntity> agentRows) {
        if (DirectConversationEntity.TYPE_AGENT.equals(peer.type())) {
            AgentEntity agent = agentRows.get(peer.id());
            return new DirectPeer(
                DirectConversationEntity.TYPE_AGENT,
                peer.id().toString(),
                agent == null ? "已停用 Agent" : agent.getName(),
                agent == null ? null : agent.getAvatarUrl());
        }
        UserEntity user = userRows.get(peer.id());
        String name =
            user == null || user.getDisplayName() == null || user.getDisplayName().isBlank()
                ? "已注销用户"
                : user.getDisplayName();
        return new DirectPeer(
            DirectConversationEntity.TYPE_USER,
            peer.id().toString(),
            name,
            user == null ? null : user.getAvatarUrl());
    }

    private DirectConversationResponse response(
        DirectConversationEntity conversation,
        ChatGroupEntity group,
        DirectPeer peer,
        GroupLatestMessageRow latest,
        Long unreadCount) {
        return new DirectConversationResponse(
            conversation.getGroupId().toString(),
            conversation.getProjectId().toString(),
            peer,
            latest == null
                ? null
                : new GroupLatestMessage(
                    latest.getSenderName(), latest.getText(), latest.getMessageType()),
            iso(group.getLastMessageAt()),
            iso(activity(group)),
            iso(group.getCreatedAt()),
            unreadCount == null ? 0L : unreadCount);
    }

    private ChatGroupEntity groupOf(Map<Long, ChatGroupEntity> groupsById, Long conversationId) {
        ChatGroupEntity group = groupsById.get(conversationId);
        if (group == null) {
            throw new IllegalStateException("私聊缺少会话行, conversationId=" + conversationId);
        }
        return group;
    }

    private long unreadCount(Long actor, Long conversationId) {
        return messages.countUnreadByGroupIds(List.of(conversationId), actor).stream()
            .mapToLong(GroupUnreadRow::getUnread)
            .findFirst()
            .orElse(0L);
    }

    /**
     * 会话列表的活跃时间：最近消息时间，从未发言时回退到创建时间。
     */
    private LocalDateTime activity(ChatGroupEntity group) {
        return group.getLastMessageAt() == null ? group.getCreatedAt() : group.getLastMessageAt();
    }

    private ApiException invalidPeer(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DIRECT_PEER_INVALID", message);
    }

    private ApiException notFound() {
        return new ApiException(
            HttpStatus.NOT_FOUND, "DIRECT_CONVERSATION_NOT_FOUND", "私聊会话不存在或无权访问");
    }

    private String iso(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }

    /**
     * 规范化后的参与者对；a 固定是用户。
     */
    private record Pair(String aType, Long aId, String bType, Long bId) {
    }

    private record Conversation(DirectConversationEntity row, ChatGroupEntity group) {
    }
}

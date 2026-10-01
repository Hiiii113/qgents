package qg.qgent.chat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.chat.dto.GroupLatestMessage;
import qg.qgent.chat.dto.GroupMemberResponse;
import qg.qgent.chat.dto.GroupPinResponse;
import qg.qgent.chat.dto.GroupReadResponse;
import qg.qgent.chat.dto.GroupResponse;
import qg.qgent.chat.dto.GroupUpdateRequest;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.mapper.GroupAgentMapper;
import qg.qgent.chat.mapper.GroupReadStateMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.view.GroupLatestMessageRow;
import qg.qgent.chat.mapper.view.GroupUnreadRow;
import qg.qgent.common.util.UuidV7;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.event.ProjectCreatedEvent;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.project.mapper.view.ProjectMembershipView;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.user.mapper.UserGroupPreferenceMapper;
import qg.qgent.user.mapper.UserMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class GroupService {
    private static final String DEFAULT_GROUP_TITLE = "项目群聊";

    private final ChatGroupMapper groupMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectMapper projectMapper;
    private final GroupAgentMapper groupAgentMapper;
    private final AgentMapper agentMapper;
    private final UserMapper userMapper;
    private final MessageMapper messageMapper;
    private final GroupReadStateMapper groupReadStateMapper;
    private final UserGroupPreferenceMapper userGroupPreferenceMapper;
    private final ProjectAccessService access;
    private final EventService eventService;

    public GroupService(ChatGroupMapper groupMapper,
                        ProjectMemberMapper projectMemberMapper,
                        ProjectMapper projectMapper, GroupAgentMapper groupAgentMapper,
                        AgentMapper agentMapper, UserMapper userMapper,
                        MessageMapper messageMapper, GroupReadStateMapper groupReadStateMapper,
                        UserGroupPreferenceMapper userGroupPreferenceMapper,
                        ProjectAccessService access, EventService eventService) {
        this.groupMapper = groupMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.projectMapper = projectMapper;
        this.groupAgentMapper = groupAgentMapper;
        this.agentMapper = agentMapper;
        this.userMapper = userMapper;
        this.messageMapper = messageMapper;
        this.groupReadStateMapper = groupReadStateMapper;
        this.userGroupPreferenceMapper = userGroupPreferenceMapper;
        this.access = access;
        this.eventService = eventService;
    }

    @EventListener
    @Transactional
    public void onProjectCreated(ProjectCreatedEvent event) {
        ensureProjectGroup(event.projectId(), event.creatorUserId(), event.projectName());
    }

    @Transactional
    public GroupResponse ensureProjectGroup(UUID projectId, UUID createdBy, String projectName) {
        ChatGroupEntity existing = groupMapper.selectOne(Wrappers.<ChatGroupEntity>lambdaQuery()
                .eq(ChatGroupEntity::getProjectId, projectId));
        if (existing != null) {
            return toResponse(existing);
        }
        String title = projectName == null || projectName.isBlank() ? DEFAULT_GROUP_TITLE
                : projectName.trim();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(UuidV7.next());
        group.setProjectId(projectId);
        group.setCreatedBy(createdBy);
        group.setName(title);
        group.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        groupMapper.insert(group);
        eventService.publish(projectId, group.getId(), "group.created", id(group.getId()),
                Map.of("projectId", id(projectId), "groupId", id(group.getId())));
        return toResponse(groupMapper.selectById(group.getId()));
    }

    public List<GroupResponse> list(UUID actor, UUID projectId) {
        access.requireProjectMember(projectId, actor);
        List<ChatGroupEntity> visibleGroups = groupMapper.listByProject(projectId);
        if (visibleGroups.isEmpty()) {
            return List.of();
        }
        List<UUID> visibleGroupIds = visibleGroups.stream().map(ChatGroupEntity::getId).toList();
        Map<UUID, GroupLatestMessageRow> latestByGroup = messageMapper.selectLatestByGroupIds(visibleGroupIds).stream()
                .collect(Collectors.toMap(GroupLatestMessageRow::getGroupId, Function.identity()));
        Map<UUID, Long> unreadByGroup = countUnread(visibleGroupIds, actor);
        Map<UUID, Long> mentionUnreadByGroup = countMentionUnread(visibleGroupIds, actor);
        Map<UUID, Boolean> pinnedByGroup = countPinned(visibleGroupIds, actor);
        return visibleGroups.stream()
                .map(group -> {
                    GroupResponse response = toResponse(group, latestByGroup.get(group.getId()),
                            unreadByGroup.get(group.getId()));
                    response.setMentionedUnread(mentionUnreadByGroup.getOrDefault(group.getId(), 0L));
                    response.setPinned(pinnedByGroup.getOrDefault(group.getId(), false));
                    return response;
                })
                .toList();
    }

    public List<GroupResponse> allGroups(UUID actor) {
        List<UUID> projectIds = projectMapper.selectAccessibleByUser(actor).stream()
                .map(ProjectMembershipView::getId).toList();
        if (projectIds.isEmpty()) {
            return List.of();
        }
        List<ChatGroupEntity> allGroups = groupMapper.selectByProjectIds(projectIds);
        if (allGroups.isEmpty()) {
            return List.of();
        }
        List<UUID> groupIds = allGroups.stream().map(ChatGroupEntity::getId).toList();
        Map<UUID, GroupLatestMessageRow> latestByGroup = messageMapper.selectLatestByGroupIds(groupIds).stream()
                .collect(Collectors.toMap(GroupLatestMessageRow::getGroupId, Function.identity()));
        Map<UUID, Long> unreadByGroup = countUnread(groupIds, actor);
        Map<UUID, Long> mentionUnreadByGroup = countMentionUnread(groupIds, actor);
        Map<UUID, Boolean> pinnedByGroup = countPinned(groupIds, actor);
        return allGroups.stream()
                .map(g -> {
                    GroupResponse response = toResponse(g, latestByGroup.get(g.getId()), unreadByGroup.get(g.getId()));
                    response.setMentionedUnread(mentionUnreadByGroup.getOrDefault(g.getId(), 0L));
                    response.setPinned(pinnedByGroup.getOrDefault(g.getId(), false));
                    return response;
                })
                .toList();
    }

    private Map<UUID, Long> countUnread(List<UUID> groupIds, UUID actor) {
        return messageMapper.countUnreadByGroupIds(groupIds, actor).stream()
                .collect(Collectors.toMap(GroupUnreadRow::getGroupId, GroupUnreadRow::getUnread));
    }

    private Map<UUID, Long> countMentionUnread(List<UUID> groupIds, UUID actor) {
        // userIdText：JSON 匹配必须用字符串 UUID（mentions 存的是 Jackson 字符串；UUID 参数
        // 受全局 UuidBinaryTypeHandler 影响以 BINARY(16) 绑定，直接 CAST 成乱码匹配不上）
        return messageMapper.countMentionUnreadByGroupIds(groupIds, actor, actor.toString()).stream()
                .collect(Collectors.toMap(GroupUnreadRow::getGroupId, GroupUnreadRow::getUnread));
    }

    private Map<UUID, Boolean> countPinned(List<UUID> groupIds, UUID actor) {
        return userGroupPreferenceMapper.selectPinnedGroupIds(actor, groupIds).stream()
                .collect(Collectors.toMap(Function.identity(), gid -> true));
    }

    public GroupResponse get(UUID actor, UUID projectId, UUID groupId) {
        requireGroupMember(projectId, groupId, actor);
        ChatGroupEntity group = requireGroupInProject(projectId, groupId);
        Map<UUID, Long> unreadByGroup = countUnread(List.of(groupId), actor);
        Map<UUID, Long> mentionUnreadByGroup = countMentionUnread(List.of(groupId), actor);
        Map<UUID, Boolean> pinnedByGroup = countPinned(List.of(groupId), actor);
        GroupResponse response = toResponse(group, null, unreadByGroup.get(groupId));
        response.setMentionedUnread(mentionUnreadByGroup.getOrDefault(groupId, 0L));
        response.setPinned(pinnedByGroup.getOrDefault(groupId, false));
        return response;
    }

    @Transactional
    public GroupResponse update(UUID actor, UUID projectId, UUID groupId, GroupUpdateRequest body) {
        access.requireProjectMember(projectId, actor);
        ChatGroupEntity group = requireGroupInProject(projectId, groupId);
        if (!group.getCreatedBy().equals(actor)) {
            access.requireProjectAdmin(projectId, actor);
        }
        groupMapper.update(null, Wrappers.<ChatGroupEntity>lambdaUpdate()
                .set(body.getTitle() != null, ChatGroupEntity::getName,
                        body.getTitle() == null ? null : body.getTitle().trim())
                .set(body.getDescription() != null, ChatGroupEntity::getDescription, body.getDescription())
                .eq(ChatGroupEntity::getId, groupId));
        eventService.publish(projectId, groupId, "group.updated", id(groupId),
                Map.of("projectId", id(projectId), "groupId", id(groupId)));
        return toResponse(groupMapper.selectById(groupId));
    }

    public List<GroupMemberResponse> members(UUID actor, UUID projectId, UUID groupId) {
        requireGroupMember(projectId, groupId, actor);
        List<GroupMemberResponse> members = new ArrayList<>();
        projectMemberMapper.selectMembers(projectId).stream()
                .map(m -> new GroupMemberResponse(m.getUserId().toString(), m.getDisplayName(), m.getAvatarUrl(),
                        null, "USER"))
                .forEach(members::add);
        // 群内参与聊天的 Agent 一并作为成员返回（群成员 = 真实用户 + Agent 混合）
        for (UUID agentId : groupAgentMapper.selectAgentIds(groupId)) {
            AgentEntity agent = agentMapper.selectById(agentId);
            if (agent != null) {
                members.add(new GroupMemberResponse(agent.getId().toString(), agent.getName(), agent.getAvatar(),
                        null, "AGENT"));
            }
        }
        return members;
    }

    public void requireGroupMember(UUID projectId, UUID groupId, UUID actor) {
        access.requireProjectMember(projectId, actor);
        requireGroupInProject(projectId, groupId);
    }

    private String id(UUID value) {
        return value == null ? null : value.toString();
    }

    @Transactional
    public GroupReadResponse markRead(UUID actor, UUID projectId, UUID groupId) {
        requireGroupMember(projectId, groupId, actor);
        // HTTP 层的 IdempotencyFilter 已负责请求回放；游标写入仍由 GREATEST 保证并发幂等。
        long latest = nextReadSequence(groupId);
        groupReadStateMapper.upsertSequence(actor, groupId, latest);
        return new GroupReadResponse(id(groupId), latest, 0L);
    }

    private long nextReadSequence(UUID groupId) {
        Long next = messageMapper.nextSequence(groupId);
        return (next == null || next <= 1) ? 0L : next - 1;
    }

    @Transactional
    public GroupPinResponse setPinned(UUID actor, UUID projectId, UUID groupId, boolean pinned) {
        requireGroupMember(projectId, groupId, actor);
        requireGroupInProject(projectId, groupId);
        userGroupPreferenceMapper.upsertPin(actor, groupId, pinned);
        return new GroupPinResponse(id(groupId), pinned);
    }

    private ChatGroupEntity requireGroupInProject(UUID projectId, UUID groupId) {
        ChatGroupEntity group = groupMapper.selectById(groupId);
        if (group == null || !group.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问");
        }
        return group;
    }

    private GroupResponse toResponse(ChatGroupEntity g) {
        return toResponse(g, null, null);
    }

    private GroupResponse toResponse(ChatGroupEntity g, GroupLatestMessageRow latest) {
        return toResponse(g, latest, null);
    }

    private GroupResponse toResponse(ChatGroupEntity g, GroupLatestMessageRow latest, Long unreadCount) {
        GroupLatestMessage latestMessage = latest == null ? null
                : new GroupLatestMessage(latest.getSenderName(), latest.getText(), latest.getMessageType());
        long members = projectMemberMapper.countMembers(g.getProjectId())
                + groupAgentMapper.selectAgentIds(g.getId()).size();
        GroupResponse response = new GroupResponse(g.getId().toString(), g.getProjectId().toString(),
                g.getName(), g.getDescription(), id(g.getCreatedBy()), iso(g.getLastMessageAt()),
                iso(g.getLastMessageAt() != null ? g.getLastMessageAt() : g.getCreatedAt()), latestMessage,
                iso(g.getCreatedAt()), members, unreadCount == null ? 0L : unreadCount, 0L, false);
        // 「@ 我」未读数默认 0，list/allGroups/get 按需覆盖
        response.setMentionedUnread(0L);
        // 置顶为个人偏好，默认 false；list/allGroups/get 按需覆盖
        response.setPinned(false);
        return response;
    }

    private String iso(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }
}

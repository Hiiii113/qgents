package qg.qgent.chat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import qg.qgent.chat.dto.GroupLatestMessage;
import qg.qgent.chat.dto.GroupCreateRequest;
import qg.qgent.chat.dto.GroupInviteRequest;
import qg.qgent.chat.mapper.GroupUserMapper;
import qg.qgent.chat.dto.GroupMemberResponse;
import qg.qgent.chat.dto.GroupPinResponse;
import qg.qgent.chat.dto.GroupReadResponse;
import qg.qgent.chat.dto.GroupResponse;
import qg.qgent.chat.dto.GroupUpdateRequest;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.QgentIdentity;
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
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.user.mapper.UserGroupPreferenceMapper;

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
    private final GroupUserMapper groupUserMapper;
    private final MessageMapper messageMapper;
    private final GroupReadStateMapper groupReadStateMapper;
    private final UserGroupPreferenceMapper userGroupPreferenceMapper;
    private final ProjectAccessService access;
    private final EventService eventService;

    public GroupService(ChatGroupMapper groupMapper,
                        ProjectMemberMapper projectMemberMapper,
                        ProjectMapper projectMapper, GroupUserMapper groupUserMapper,
                        MessageMapper messageMapper, GroupReadStateMapper groupReadStateMapper,
                        UserGroupPreferenceMapper userGroupPreferenceMapper,
                        ProjectAccessService access, EventService eventService) {
        this.groupMapper = groupMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.projectMapper = projectMapper;
        this.groupUserMapper = groupUserMapper;
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
                .eq(ChatGroupEntity::getProjectId, projectId).eq(ChatGroupEntity::getDefaultGroup, true));
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
        group.setDefaultGroup(true);
        group.setQgentEnabled(true);
        group.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        groupMapper.insert(group);
        eventService.publish(projectId, group.getId(), "group.created", id(group.getId()),
                Map.of("projectId", id(projectId), "groupId", id(group.getId())));
        return toResponse(group);
    }

    public List<GroupResponse> list(UUID actor, UUID projectId) {
        access.requireProjectMember(projectId, actor);
        return groupResponses(groupMapper.listByProject(projectId), actor);
    }

    /** 列表与详情共用消息摘要、未读数和个人置顶状态的组装逻辑。 */
    private List<GroupResponse> groupResponses(List<ChatGroupEntity> groups, UUID actor) {
        groups = groups.stream().filter(group -> isDefault(group) || groupUserMapper.selectUserIds(group.getId()).contains(actor)).toList();
        if (groups.isEmpty()) {
            return List.of();
        }
        List<UUID> visibleGroupIds = groups.stream().map(ChatGroupEntity::getId).toList();
        Map<UUID, GroupLatestMessageRow> latestByGroup = messageMapper.selectLatestByGroupIds(visibleGroupIds).stream()
                .collect(Collectors.toMap(GroupLatestMessageRow::getGroupId, Function.identity()));
        Map<UUID, Long> unreadByGroup = countUnread(visibleGroupIds, actor);
        Map<UUID, Long> mentionUnreadByGroup = countMentionUnread(visibleGroupIds, actor);
        Map<UUID, Boolean> pinnedByGroup = countPinned(visibleGroupIds, actor);
        return groups.stream()
                .map(group -> {
                    GroupResponse response = toResponse(group, latestByGroup.get(group.getId()),
                            unreadByGroup.get(group.getId()));
                    response.setMentionedUnread(mentionUnreadByGroup.getOrDefault(group.getId(), 0L));
                    response.setPinned(pinnedByGroup.getOrDefault(group.getId(), false));
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
        ChatGroupEntity group = requireGroupMember(projectId, groupId, actor);
        return groupResponses(List.of(group), actor).getFirst();
    }

    @Transactional
    public GroupResponse update(UUID actor, UUID projectId, UUID groupId, GroupUpdateRequest body) {
        ChatGroupEntity group = requireGroupMember(projectId, groupId, actor);
        if (!group.getCreatedBy().equals(actor)) {
            access.requireProjectAdmin(projectId, actor);
        }
        if (body.getTitle() != null && body.getTitle().isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GROUP_TITLE_REQUIRED", "群名称不能为空");
        }
        if (body.getTitle() == null) {
            return get(actor, projectId, groupId);
        }
        groupMapper.update(null, Wrappers.<ChatGroupEntity>lambdaUpdate()
                .set(ChatGroupEntity::getName, body.getTitle().trim())
                .eq(ChatGroupEntity::getId, groupId));
        eventService.publish(projectId, groupId, "group.updated", id(groupId),
                Map.of("projectId", id(projectId), "groupId", id(groupId)));
        return get(actor, projectId, groupId);
    }

    public List<GroupMemberResponse> members(UUID actor, UUID projectId, UUID groupId) {
        ChatGroupEntity group = requireGroupMember(projectId, groupId, actor);
        List<GroupMemberResponse> members = new ArrayList<>();
        boolean defaultGroup = isDefault(group);
        var userMembers = defaultGroup ? projectMemberMapper.selectMembers(projectId) : groupUserMapper.selectMembers(groupId);
        userMembers.stream()
                .map(m -> new GroupMemberResponse(m.getUserId().toString(), m.getDisplayName(), m.getAvatarUrl(),
                        null, "USER"))
                .forEach(members::add);
        if (Boolean.TRUE.equals(group.getQgentEnabled())) {
            members.add(new GroupMemberResponse(QgentIdentity.ID.toString(), QgentIdentity.NAME, null, null, "AGENT"));
        }
        return members;
    }

    public ChatGroupEntity requireGroupMember(UUID projectId, UUID groupId, UUID actor) {
        access.requireProjectMember(projectId, actor);
        ChatGroupEntity group = requireGroupInProject(projectId, groupId);
        if (!isDefault(group) && !groupUserMapper.selectUserIds(groupId).contains(actor)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问");
        }
        return group;
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

    private GroupResponse toResponse(ChatGroupEntity g, GroupLatestMessageRow latest, Long unreadCount) {
        GroupLatestMessage latestMessage = latest == null ? null
                : new GroupLatestMessage(latest.getSenderName(), latest.getText(), latest.getMessageType());
        long members = (isDefault(g) ? projectMemberMapper.countMembers(g.getProjectId()) : groupUserMapper.selectUserIds(g.getId()).size())
                + (Boolean.TRUE.equals(g.getQgentEnabled()) ? 1 : 0);
        GroupResponse response = new GroupResponse(g.getId().toString(), g.getProjectId().toString(),
                g.getName(), id(g.getCreatedBy()), iso(g.getLastMessageAt()),
                iso(g.getLastMessageAt() != null ? g.getLastMessageAt() : g.getCreatedAt()), latestMessage,
                iso(g.getCreatedAt()), members, unreadCount == null ? 0L : unreadCount, 0L, false, isDefault(g));
        return response;
    }

    private boolean isDefault(ChatGroupEntity group) {
        return Boolean.TRUE.equals(group.getDefaultGroup());
    }

    /** 项目成员可以建群，创建者自动加入；邀请范围限于当前项目及其团队 Agent。 */
    @Transactional
    public GroupResponse create(UUID actor, UUID projectId, GroupCreateRequest body) {
        access.requireProjectMember(projectId, actor);
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(UuidV7.next());
        group.setProjectId(projectId);
        group.setCreatedBy(actor);
        group.setDefaultGroup(false);
        group.setName(body.getTitle().trim());
        group.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        validateInvites(actor, projectId, body.getUserIds(), body.getAgentIds());
        group.setQgentEnabled(body.getAgentIds().contains(QgentIdentity.ID));
        groupMapper.insert(group);
        groupUserMapper.insertUser(group.getId(), actor);
        addMembers(group.getId(), body.getUserIds());
        eventService.publish(projectId, group.getId(), "group.created", id(group.getId()),
                Map.of("projectId", id(projectId), "groupId", id(group.getId())));
        return toResponse(group);
    }

    @Transactional
    public List<GroupMemberResponse> invite(UUID actor, UUID projectId, UUID groupId, GroupInviteRequest body) {
        ChatGroupEntity group = requireGroupMember(projectId, groupId, actor);
        validateInvites(actor, projectId, body.getUserIds(), body.getAgentIds());
        // 默认群的用户随项目同步，只需追加 Agent。
        addMembers(groupId, isDefault(group) ? List.of() : body.getUserIds());
        if (body.getAgentIds().contains(QgentIdentity.ID)) {
            groupMapper.update(null, Wrappers.<ChatGroupEntity>lambdaUpdate()
                    .set(ChatGroupEntity::getQgentEnabled, true).eq(ChatGroupEntity::getId, groupId));
            group.setQgentEnabled(true);
        }
        eventService.publish(projectId, groupId, "group.member.updated", id(groupId),
                Map.of("projectId", id(projectId), "groupId", id(groupId)));
        return members(actor, projectId, groupId);
    }

    private void validateInvites(UUID actor, UUID projectId, List<UUID> users, List<UUID> agents) {
        if (users == null || agents == null || users.stream().anyMatch(java.util.Objects::isNull) || agents.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_GROUP_MEMBER", "请选择有效成员");
        }
        for (UUID userId : users) access.requireProjectMember(projectId, userId);
        if (agents.stream().anyMatch(id -> !QgentIdentity.ID.equals(id))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_GROUP_AGENT", "只能邀请 Qgent");
        }
    }

    private void addMembers(UUID groupId, List<UUID> users) {
        users.stream().distinct().forEach(userId -> groupUserMapper.insertUser(groupId, userId));
    }

    private String iso(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }
}

package qg.qgent.project.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import qg.qgent.common.dto.PageInfo;
import qg.qgent.common.dto.PageSlice;
import qg.qgent.common.util.UuidV7;
import qg.qgent.common.web.ApiException;
import qg.qgent.notification.service.NotificationService;
import qg.qgent.project.dto.ProjectCreateRequest;
import qg.qgent.project.dto.ProjectMemberAddRequest;
import qg.qgent.project.dto.ProjectMemberResponse;
import qg.qgent.project.dto.ProjectMemberUpdateRequest;
import qg.qgent.project.dto.ProjectResponse;
import qg.qgent.project.dto.ProjectUpdateRequest;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.entity.ProjectMemberEntity;
import qg.qgent.project.event.ProjectCreatedEvent;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.project.mapper.view.ProjectMembershipView;
import qg.qgent.realtime.service.EventService;
import qg.qgent.team.entity.TeamEntity;
import qg.qgent.team.entity.TeamMemberEntity;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.team.mapper.TeamMemberMapper;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class ProjectService {
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper memberMapper;
    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final ProjectAccessService access;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationService notificationService;
    private final EventService eventService;

    /** 创建项目并初始化项目成员关系。 */
    @Transactional
    public ProjectResponse create(UUID actor, UUID teamId, ProjectCreateRequest request) {
        TeamEntity team = requireTeamForUpdate(teamId);
        requireCanonicalTeamOwner(team, actor);
        ProjectEntity project = new ProjectEntity();
        project.setId(UuidV7.next());
        project.setTeamId(teamId);
        project.setCreatedBy(actor);
        project.setName(request.getName().trim());
        project.setDescription(request.getDescription());
        project.setStatus("ACTIVE");
        projectMapper.insert(project);

        insertMember(project.getId(), actor, "PROJECT_ADMIN");
        for (UUID userId : new LinkedHashSet<>(request.getMemberIds() == null ? List.of() : request.getMemberIds())) {
            if (actor.equals(userId)) {
                continue;
            }
            requireTeamMember(teamId, userId);
            insertMember(project.getId(), userId, "PROJECT_MEMBER");
        }
        eventPublisher.publishEvent(new ProjectCreatedEvent(project.getId(), project.getName(), actor));
        // 项目创建动态事件：供「团队最近动态」聚合展示，与项目同事务落库
        eventService.publish(project.getId(), null, "project.created", project.getId().toString(),
                Map.of("projectId", project.getId(), "name", project.getName(), "createdBy", actor));
        return response(project, "PROJECT_ADMIN");
    }

    public PageSlice<ProjectResponse> list(UUID actor, UUID teamId, String cursor, Integer limit) {
        requireTeam(teamId);
        requireTeamMember(teamId, actor);
        boolean teamOwner = access.isCanonicalTeamOwner(teamId, actor);
        int size = pageSize(limit);
        String scope = "team-projects:" + teamId + ":" + actor;
        UUID anchor = decodeCursor(cursor, scope);
        List<ProjectMembershipView> rows = projectMapper.selectAccessiblePage(teamId, actor, teamOwner, anchor,
                size + 1);
        return keysetPage(rows, size, scope, ProjectMembershipView::getId,
                this::response);
    }

    /** 按最近聊天时间返回可见项目；无消息的项目排在末尾，不分页。 */
    public List<ProjectResponse> listByLastActivity(UUID actor, UUID teamId) {
        requireTeam(teamId);
        requireTeamMember(teamId, actor);
        boolean teamOwner = access.isCanonicalTeamOwner(teamId, actor);
        return projectMapper.selectAccessibleByActivity(teamId, actor, teamOwner).stream()
                .map(this::response)
                .toList();
    }

    public ProjectResponse get(UUID actor, UUID projectId) {
        ProjectEntity project = requireActiveProject(projectId);
        return response(project, access.requireAccess(project, actor));
    }

    @Transactional
    public ProjectResponse update(UUID actor, UUID projectId, ProjectUpdateRequest request) {
        ProjectEntity project = requireProjectForUpdate(projectId);
        access.requireProjectAdminAnyState(project, actor);
        requireActive(project);
        // PATCH 语义：name/description/avatarUrl 均可单独更新；全 null 时拒绝空更新
        if (request.getName() == null && request.getDescription() == null && request.getAvatarUrl() == null) {
            throw invalid("至少需要提供 name、description 或 avatarUrl");
        }
        if (request.getName() != null) {
            if (request.getName().isBlank()) {
                throw invalid("项目名称不能为空");
            }
            project.setName(request.getName().trim());
        }
        if (request.getDescription() != null) {
            project.setDescription(request.getDescription());
        }
        // avatarUrl：null 保留原值；非 null（含空串清空）覆盖保存
        if (request.getAvatarUrl() != null) {
            project.setAvatarUrl(blankToNull(request.getAvatarUrl()));
        }
        projectMapper.updateById(project);
        return response(project, "PROJECT_ADMIN");
    }

    @Transactional
    public ProjectResponse archive(UUID actor, UUID projectId) {
        return transition(actor, projectId, "ARCHIVED");
    }

    @Transactional
    public ProjectResponse restore(UUID actor, UUID projectId) {
        return transition(actor, projectId, "ACTIVE");
    }

    public PageSlice<ProjectMemberResponse> members(UUID actor, UUID projectId, String cursor, Integer limit) {
        ProjectEntity project = requireActiveProject(projectId);
        access.requireAccess(project, actor);
        int size = pageSize(limit);
        String scope = "project-members:" + projectId;
        UUID anchor = decodeCursor(cursor, scope);
        List<ProjectMemberEntity> rows = memberMapper.selectMemberPage(projectId, anchor, size + 1);
        return keysetPage(rows, size, scope, ProjectMemberEntity::getUserId,
                member -> new ProjectMemberResponse(member.getUserId().toString(), member.getRole()));
    }

    @Transactional
    public ProjectMemberResponse addMember(UUID actor, UUID projectId, ProjectMemberAddRequest request) {
        ProjectEntity project = lockTeamThenProject(projectId);
        access.requireProjectAdminAnyState(project, actor);
        requireActive(project);
        requireTeamMember(project.getTeamId(), request.getUserId());
        if (memberMapper.selectByProjectAndUser(projectId, request.getUserId()) != null) {
            throw conflict("PROJECT_MEMBER_ALREADY_EXISTS", "用户已是项目成员");
        }
        insertMember(projectId, request.getUserId(), "PROJECT_MEMBER");
        // 通知被加入项目的成员
        notificationService.notify(request.getUserId(), projectId, null, "PROJECT_ADDED",
                "你被加入项目 " + project.getName(), null, projectId.toString());
        // 告知团队在线客户端有新项目成员。
        eventService.publishTeamEvent(project.getTeamId(), "project.member.added", id(projectId),
                Map.of("teamId", id(project.getTeamId()), "projectId", id(projectId)));
        return new ProjectMemberResponse(request.getUserId().toString(), "PROJECT_MEMBER");
    }

    private String id(UUID value) {
        return value == null ? null : value.toString();
    }

    @Transactional
    public ProjectMemberResponse updateMember(UUID actor, UUID projectId, UUID userId,
                                              ProjectMemberUpdateRequest request) {
        ProjectEntity project = lockTeamThenProject(projectId);
        access.requireProjectAdminAnyState(project, actor);
        requireActive(project);
        if (!List.of("PROJECT_ADMIN", "PROJECT_MEMBER").contains(request.getRole())) {
            throw invalid("项目角色仅支持 PROJECT_ADMIN 或 PROJECT_MEMBER");
        }
        ProjectMemberEntity target = requireProjectMember(projectId, userId);
        // 管理员设置后不可撤销：降级一律拒绝；已生效的相同角色请求幂等返回，不重复写库。
        if ("PROJECT_ADMIN".equals(target.getRole()) && !"PROJECT_ADMIN".equals(request.getRole())) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_ADMIN_IMMUTABLE", "项目管理员不可降级（设置后不可撤销）");
        }
        if ("PROJECT_ADMIN".equals(target.getRole())) {
            return new ProjectMemberResponse(userId.toString(), target.getRole());
        }
        memberMapper.updateRole(projectId, userId, request.getRole());
        return new ProjectMemberResponse(userId.toString(), request.getRole());
    }

    @Transactional
    public ProjectMemberResponse removeMember(UUID actor, UUID projectId, UUID userId) {
        ProjectEntity project = lockTeamThenProject(projectId);
        access.requireProjectAdminAnyState(project, actor);
        requireActive(project);
        ProjectMemberEntity target = requireProjectMember(projectId, userId);
        // 管理员不可被其他管理员移除（含移除自己）。
        if ("PROJECT_ADMIN".equals(target.getRole())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_CANNOT_REMOVE_MANAGER", "项目管理员不可被移除");
        }
        memberMapper.deleteByProjectAndUser(projectId, userId);
        return new ProjectMemberResponse(userId.toString(), target.getRole());
    }

    private ProjectResponse transition(UUID actor, UUID projectId, String targetStatus) {
        ProjectEntity project = requireProjectForUpdate(projectId);
        access.requireAdmin(project, actor);
        if (!List.of("ACTIVE", "ARCHIVED").contains(project.getStatus())) {
            throw conflict("INVALID_PROJECT_STATE", "项目状态不允许变更");
        }
        // 重复归档或恢复返回当前结果；不同幂等键重试也不会制造额外状态变化。
        if (!targetStatus.equals(project.getStatus())) {
            project.setStatus(targetStatus);
            projectMapper.updateById(project);
        }
        return response(project, "PROJECT_ADMIN");
    }

    private void insertMember(UUID projectId, UUID userId, String role) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setRole(role);
        memberMapper.insert(member);
    }

    private void requireCanonicalTeamOwner(TeamEntity team, UUID actor) {
        TeamMemberEntity member = requireTeamMember(team.getId(), actor);
        if (!actor.equals(team.getOwnerUserId()) || !"TEAM_OWNER".equals(member.getRole())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TEAM_OWNER_REQUIRED", "需要 Team Owner 权限");
        }
    }

    private TeamMemberEntity requireTeamMember(UUID teamId, UUID userId) {
        TeamMemberEntity member = teamMemberMapper.selectByTeamAndUser(teamId, userId);
        if (member == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TEAM_MEMBER_REQUIRED", "用户必须属于该团队");
        }
        return member;
    }

    private ProjectMemberEntity requireProjectMember(UUID projectId, UUID userId) {
        ProjectMemberEntity member = memberMapper.selectByProjectAndUser(projectId, userId);
        if (member == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_MEMBER_NOT_FOUND", "项目成员不存在");
        }
        return member;
    }

    private TeamEntity requireTeam(UUID teamId) {
        TeamEntity team = teamMapper.selectById(teamId);
        if (team == null || !"ACTIVE".equals(team.getStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "团队不存在或不可见");
        }
        return team;
    }

    private TeamEntity requireTeamForUpdate(UUID teamId) {
        TeamEntity team = teamMapper.selectByIdForUpdate(teamId);
        if (team == null || !"ACTIVE".equals(team.getStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "团队不存在或不可见");
        }
        return team;
    }

    private ProjectEntity requireActiveProject(UUID projectId) {
        ProjectEntity project = projectMapper.selectById(projectId);
        if (project == null || !"ACTIVE".equals(project.getStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见");
        }
        return project;
    }

    private ProjectEntity requireProjectForUpdate(UUID projectId) {
        ProjectEntity project = projectMapper.selectByIdForUpdate(projectId);
        if (project == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见");
        }
        return project;
    }

    /** 先锁团队再锁项目，与团队成员删除保持相同顺序。 */
    private ProjectEntity lockTeamThenProject(UUID projectId) {
        ProjectEntity observed = projectMapper.selectById(projectId);
        if (observed == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见");
        }
        TeamEntity team = requireTeamForUpdate(observed.getTeamId());
        ProjectEntity locked = requireProjectForUpdate(projectId);
        if (!team.getId().equals(locked.getTeamId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见");
        }
        return locked;
    }

    private void requireActive(ProjectEntity project) {
        if (!"ACTIVE".equals(project.getStatus())) {
            throw conflict("PROJECT_ARCHIVED", "归档项目不可执行该操作");
        }
    }

    private ProjectResponse response(ProjectEntity project, String role) {
        ProjectResponse r = new ProjectResponse(project.getId().toString(), project.getTeamId().toString(),
                project.getName(), project.getDescription(), role, project.getStatus(),
                memberMapper.countMembers(project.getId()));
        r.setAvatarUrl(project.getAvatarUrl());
        return r;
    }

    private ProjectResponse response(ProjectMembershipView row) {
        ProjectResponse r = new ProjectResponse(row.getId().toString(), row.getTeamId().toString(), row.getName(),
                row.getDescription(), row.getRole(), row.getStatus(), row.getMemberCount());
        r.setAvatarUrl(row.getAvatarUrl());
        if (row.getLastActivityAt() != null) {
            r.setLastActivityAt(row.getLastActivityAt().toInstant(ZoneOffset.UTC).toString());
        }
        return r;
    }

    private int pageSize(Integer requested) {
        int value = requested == null ? 30 : requested;
        if (value < 1 || value > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_LIMIT", "limit 必须在 1 到 100 之间");
        }
        return value;
    }

    private UUID decodeCursor(String cursor, String expectedScope) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = value.lastIndexOf('|');
            if (separator <= 0 || !expectedScope.equals(value.substring(0, separator))) {
                throw new IllegalArgumentException();
            }
            return UUID.fromString(value.substring(separator + 1));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "cursor 无效");
        }
    }

    private String encodeCursor(String scope, UUID anchor) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((scope + "|" + anchor).getBytes(StandardCharsets.UTF_8));
    }

    private <S, T> PageSlice<T> keysetPage(List<S> rows, int size, String scope, Function<S, UUID> id,
                                           Function<S, T> mapper) {
        boolean hasMore = rows.size() > size;
        List<S> visible = hasMore ? rows.subList(0, size) : rows;
        List<T> data = new ArrayList<>(visible.size());
        visible.forEach(row -> data.add(mapper.apply(row)));
        String next = hasMore ? encodeCursor(scope, id.apply(visible.get(visible.size() - 1))) : null;
        return new PageSlice<>(data, new PageInfo(next, hasMore));
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PROJECT_OPERATION", message);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}

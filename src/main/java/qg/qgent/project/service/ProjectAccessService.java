package qg.qgent.project.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import qg.qgent.common.web.ApiException;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.entity.ProjectMemberEntity;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.team.entity.TeamEntity;
import qg.qgent.team.entity.TeamMemberEntity;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.team.mapper.TeamMemberMapper;


/** 服务端项目访问边界，不信任客户端提交的角色或用户标识。 */
@Service
@RequiredArgsConstructor
public class ProjectAccessService {
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;

    public String requireProjectMember(Long projectId, Long userId) {
        ProjectEntity project = requireProject(projectId, false);
        return requireAccess(project, userId);
    }

    public void requireProjectAdmin(Long projectId, Long userId) {
        requireAdmin(requireProject(projectId, false), userId);
    }

    /** 归档项目必须仍可授权恢复，因此该入口允许读取 ARCHIVED 项目。 */
    public void requireProjectAdminAnyState(Long projectId, Long userId) {
        requireAdmin(requireProject(projectId, true), userId);
    }

    /** 对已加锁项目执行不限状态的 Admin 校验，供状态错误前的防泄漏授权使用。 */
    public void requireProjectAdminAnyState(ProjectEntity project, Long userId) {
        requireAdmin(project, userId);
    }

    public String requireAccess(ProjectEntity project, Long userId) {
        // canonical Team Owner 是跨项目兜底管理员，不要求存在 project_members 行。
        if (isCanonicalTeamOwner(project.getTeamId(), userId)) {
            return "PROJECT_ADMIN";
        }
        ProjectMemberEntity member = projectMemberMapper.selectByProjectAndUser(project.getId(), userId);
        if (member == null) {
            throw notFound();
        }
        return member.getRole();
    }

    public void requireAdmin(ProjectEntity project, Long userId) {
        if (isCanonicalTeamOwner(project.getTeamId(), userId)) {
            return;
        }
        ProjectMemberEntity member = projectMemberMapper.selectByProjectAndUser(project.getId(), userId);
        if (member != null && "PROJECT_ADMIN".equals(member.getRole())) {
            return;
        }
        if (member == null) {
            throw notFound();
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限");
    }

    /** 同时核对 teams.owner_user_id 与成员角色，额外 TEAM_OWNER 不能获得兜底权限。 */
    public boolean isCanonicalTeamOwner(Long teamId, Long userId) {
        TeamEntity team = teamMapper.selectById(teamId);
        if (team == null || !userId.equals(team.getOwnerUserId())) {
            return false;
        }
        TeamMemberEntity member = teamMemberMapper.selectByTeamAndUser(teamId, userId);
        return member != null && "TEAM_OWNER".equals(member.getRole());
    }

    /** 判断当前用户是否为项目 Admin（含 canonical Team Owner 兜底）；供能力派生使用。 */
    public boolean isProjectAdmin(Long projectId, Long userId) {
        try {
            requireProjectAdmin(projectId, userId);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    /** 校验调用者为团队成员（团队不存在或非成员返回 404，防枚举）。 */
    public void requireTeamMember(Long teamId, Long userId) {
        if (teamMemberMapper.selectByTeamAndUser(teamId, userId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TEAM_RESOURCE_NOT_FOUND", "团队资源不存在或不可见");
        }
    }

    private ProjectEntity requireProject(Long projectId, boolean allowArchived) {
        ProjectEntity project = projectMapper.selectById(projectId);
        if (project == null || (!allowArchived && !"ACTIVE".equals(project.getStatus()))) {
            throw notFound();
        }
        return project;
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见");
    }
}

package qg.qgent.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.attachment.storage.AvatarStorageService;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.dto.ProjectResponse;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.project.service.ProjectViews;
import qg.qgent.team.dto.TeamResponse;
import qg.qgent.team.entity.TeamEntity;
import qg.qgent.team.entity.TeamMemberEntity;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.team.mapper.TeamMemberMapper;
import qg.qgent.team.mapper.view.TeamMemberCountRow;
import qg.qgent.team.service.TeamViews;
import qg.qgent.user.dto.CurrentUserResponse;
import qg.qgent.user.dto.UserResponse;
import qg.qgent.user.dto.UserUpdateRequest;
import qg.qgent.user.entity.UserEntity;
import qg.qgent.user.mapper.UserMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 当前账户资料：账户摘要、昵称/头像修改与当前团队选择。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final ProjectMapper projectMapper;
    private final AvatarStorageService avatarStorage;

    /**
     * 获取当前账户、团队与项目角色摘要；最近团队不可用时回退到首个可用团队并持久化。
     */
    @Transactional
    public CurrentUserResponse me(Long userId) {
        UserEntity user = requireUser(userId);
        List<TeamResponse> availableTeams = teams(userId);
        Long currentTeamId =
            availableTeams.stream()
                .map(team -> Long.valueOf(team.getId()))
                .filter(id -> id.equals(user.getLastTeamId()))
                .findFirst()
                .orElseGet(() ->
                    availableTeams.isEmpty()
                        ? null
                        : Long.valueOf(availableTeams.getFirst().getId()));
        if (!Objects.equals(currentTeamId, user.getLastTeamId())) {
            saveTeam(userId, currentTeamId);
        }
        return new CurrentUserResponse(
            view(user),
            availableTeams,
            projects(userId),
            currentTeamId == null ? null : currentTeamId.toString());
    }

    /**
     * 保存当前团队，供后续登录和其他设备恢复；非成员或已解散团队返回 403。
     */
    @Transactional
    public void selectTeam(Long userId, Long teamId) {
        TeamEntity team = teamMapper.selectById(teamId);
        if (team == null
            || !"ACTIVE".equals(team.getStatus())
            || teamMemberMapper.selectByTeamAndUser(teamId, userId) == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TEAM_ACCESS_DENIED", "暂无团队访问权限");
        }
        saveTeam(userId, teamId);
    }

    /**
     * 修改昵称或清空头像；头像只能通过上传确认接口设置，这里拒绝外链地址。
     */
    @Transactional
    public UserResponse updateMe(Long userId, UserUpdateRequest input) {
        if (input.getDisplayName() == null && input.getAvatarUrl() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "至少提供一个修改字段");
        }
        String name = input.getDisplayName() == null ? null : input.getDisplayName().trim();
        if (name != null && name.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "昵称不能为空");
        }
        String avatar = input.getAvatarUrl() == null ? null : input.getAvatarUrl().trim();
        if (avatar != null && !avatar.isEmpty()) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST, "AVATAR_URL_NOT_ALLOWED", "头像只能通过头像上传接口设置");
        }
        // 只改目标字段，避免覆盖并发写入的最近团队选择
        String previousAvatar = avatar == null ? null : requireUser(userId).getAvatarUrl();
        userMapper.update(
            null,
            Wrappers.<UserEntity>lambdaUpdate()
                .set(name != null, UserEntity::getDisplayName, name)
                .set(
                    avatar != null,
                    UserEntity::getAvatarUrl,
                    avatar == null || avatar.isEmpty() ? null : avatar)
                .eq(UserEntity::getId, userId));
        if (avatar != null) {
            avatarStorage.deleteReplacedAfterCommit("avatars", userId, previousAvatar, null);
        }
        return view(requireUser(userId));
    }

    private void saveTeam(Long userId, Long teamId) {
        userMapper.updateLastTeam(userId, teamId);
    }

    private UserEntity requireUser(Long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "用户不存在");
        }
        return user;
    }

    private List<TeamResponse> teams(Long userId) {
        List<TeamMemberEntity> members = teamMemberMapper.selectByUserId(userId);
        if (members.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, TeamEntity> teams =
            teamMapper
                .selectBatchIds(members.stream().map(TeamMemberEntity::getTeamId).toList())
                .stream()
                .filter(team -> "ACTIVE".equals(team.getStatus()))
                .collect(Collectors.toMap(TeamEntity::getId, Function.identity()));
        Map<Long, Integer> memberCounts = memberCounts(teams.keySet());
        return members.stream()
            .filter(member -> teams.containsKey(member.getTeamId()))
            .map(member -> {
                TeamEntity team = teams.get(member.getTeamId());
                String role =
                    ProjectAccessService.effectiveTeamRole(
                        team.getOwnerUserId(), userId, member.getRole());
                return TeamViews.response(
                    team, role, memberCounts.getOrDefault(team.getId(), 0));
            })
            .toList();
    }

    /**
     * 一次查询取回所有团队的人数；逐团队 COUNT 会随团队数量线性增长。
     */
    private Map<Long, Integer> memberCounts(Set<Long> teamIds) {
        if (teamIds.isEmpty()) return Map.of();
        return teamMemberMapper.countByTeamIds(teamIds).stream()
            .collect(
                Collectors.toMap(
                    TeamMemberCountRow::getTeamId, TeamMemberCountRow::getMemberCount));
    }

    private List<ProjectResponse> projects(Long userId) {
        // 单次查询合并项目成员权限与 canonical Team Owner 兜底，并由 SQL 去重。
        // memberCount 由项目列表/详情接口补齐，此处不额外统计（前端缺失时隐藏）。
        return projectMapper.selectAccessibleByUser(userId).stream()
            .map(ProjectViews::response)
            .toList();
    }

    private UserResponse view(UserEntity user) {
        return new UserResponse(
            user.getId().toString(),
            user.getEmail(),
            user.getDisplayName(),
            user.getAvatarUrl());
    }
}

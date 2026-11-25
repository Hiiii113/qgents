package qg.qgent.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qg.qgent.attachment.storage.AvatarStorageService;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.mapper.view.ProjectMembershipView;
import qg.qgent.support.TestIds;
import qg.qgent.team.entity.TeamEntity;
import qg.qgent.team.entity.TeamMemberEntity;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.team.mapper.TeamMemberMapper;
import qg.qgent.team.mapper.view.TeamMemberCountRow;
import qg.qgent.user.entity.UserEntity;
import qg.qgent.user.mapper.UserMapper;

/** 当前账户摘要：团队回退与持久化、团队角色换算、项目可见性与当前团队选择。 */
class UserServiceTest {

    private static final String AVATAR = "https://example.test/avatar.png";

    private final UserMapper users = mock(UserMapper.class);
    private final TeamMapper teams = mock(TeamMapper.class);
    private final TeamMemberMapper members = mock(TeamMemberMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final Long userId = TestIds.next();
    private final Long firstTeamId = TestIds.next();
    private final Long secondTeamId = TestIds.next();
    private final UserEntity user = new UserEntity();
    private UserService service;

    @BeforeEach
    void setUp() {
        service =
                new UserService(
                        users,
                        teams,
                        members,
                        projects,
                        mock(AvatarStorageService.class));
        user.setId(userId);
        when(users.selectById(userId)).thenReturn(user);
    }

    @Test
    void restoresLastAccessibleTeamAndIncludesAvatar() {
        availableTeams(firstTeamId, secondTeamId);
        user.setLastTeamId(secondTeamId);

        var response = service.me(userId);

        assertThat(response.getCurrentTeamId()).isEqualTo(secondTeamId.toString());
        assertThat(response.getTeams().getFirst().getAvatarUrl()).isEqualTo(AVATAR);
        verify(users, never()).updateLastTeam(any(), any());
    }

    @Test
    void fallsBackAndPersistsWhenLastTeamIsUnavailable() {
        availableTeams(firstTeamId);
        user.setLastTeamId(secondTeamId);

        assertThat(service.me(userId).getCurrentTeamId()).isEqualTo(firstTeamId.toString());

        verify(users).updateLastTeam(userId, firstTeamId);
    }

    @Test
    void clearsSelectionWhenNoTeamsRemain() {
        user.setLastTeamId(secondTeamId);
        when(members.selectByUserId(userId)).thenReturn(List.of());

        assertThat(service.me(userId).getCurrentTeamId()).isNull();

        verify(users).updateLastTeam(userId, null);
    }

    @Test
    void reportsMemberCountForEveryTeam() {
        availableTeams(firstTeamId, secondTeamId);
        when(members.countByTeamIds(anyCollection()))
                .thenReturn(List.of(counted(firstTeamId, 2), counted(secondTeamId, 5)));

        var responseTeams = service.me(userId).getTeams();

        assertThat(responseTeams.get(0).getMemberCount()).isEqualTo(2);
        assertThat(responseTeams.get(1).getMemberCount()).isEqualTo(5);
        verify(members).countByTeamIds(anyCollection());
    }

    @Test
    void reportsStoredTeamAdminRole() {
        available(List.of(membership(firstTeamId, "TEAM_ADMIN")), List.of(team(firstTeamId)));

        assertThat(service.me(userId).getTeams().getFirst().getRole()).isEqualTo("TEAM_ADMIN");
    }

    @Test
    void extraOwnerRoleWithoutCanonicalOwnershipStaysTeamMember() {
        available(List.of(membership(firstTeamId, "TEAM_OWNER")), List.of(team(firstTeamId)));

        assertThat(service.me(userId).getTeams().getFirst().getRole()).isEqualTo("TEAM_MEMBER");
    }

    @Test
    void canonicalOwnerSeesProjectsWithoutProjectMembership() {
        ProjectMembershipView project = project(firstTeamId, "PROJECT_ADMIN");
        when(members.selectByUserId(userId)).thenReturn(List.of());
        when(projects.selectAccessibleByUser(userId)).thenReturn(List.of(project));

        var response = service.me(userId);

        assertThat(response.getProjects()).hasSize(1);
        assertThat(response.getProjects().getFirst().getId()).isEqualTo(project.getId().toString());
        assertThat(response.getProjects().getFirst().getRole()).isEqualTo("PROJECT_ADMIN");
    }

    @Test
    void savesSelectedTeamForMembers() {
        when(teams.selectById(firstTeamId)).thenReturn(team(firstTeamId));
        when(members.selectByTeamAndUser(firstTeamId, userId)).thenReturn(new TeamMemberEntity());

        service.selectTeam(userId, firstTeamId);

        verify(users).updateLastTeam(userId, firstTeamId);
    }

    @Test
    void rejectsNonMemberAndDisbandedTeamWithoutSaving() {
        when(teams.selectById(firstTeamId)).thenReturn(team(firstTeamId));
        assertThatThrownBy(() -> service.selectTeam(userId, firstTeamId))
                .isInstanceOf(ApiException.class);

        TeamEntity disbanded = team(secondTeamId);
        disbanded.setStatus("DISBANDED");
        when(teams.selectById(secondTeamId)).thenReturn(disbanded);
        assertThatThrownBy(() -> service.selectTeam(userId, secondTeamId))
                .isInstanceOf(ApiException.class);

        verify(users, never()).updateLastTeam(any(), any());
    }

    private void availableTeams(Long... teamIds) {
        available(
                Arrays.stream(teamIds).map(id -> membership(id, "TEAM_MEMBER")).toList(),
                Arrays.stream(teamIds).map(this::team).toList());
    }

    private void available(List<TeamMemberEntity> memberships, List<TeamEntity> teamEntities) {
        when(members.selectByUserId(userId)).thenReturn(memberships);
        when(teams.selectBatchIds(anyCollection())).thenReturn(teamEntities);
        when(members.countByTeamIds(anyCollection())).thenReturn(List.of());
    }

    private TeamMemberEntity membership(Long teamId, String role) {
        TeamMemberEntity membership = new TeamMemberEntity();
        membership.setTeamId(teamId);
        membership.setUserId(userId);
        membership.setRole(role);
        return membership;
    }

    private TeamMemberCountRow counted(Long teamId, int count) {
        TeamMemberCountRow row = new TeamMemberCountRow();
        row.setTeamId(teamId);
        row.setMemberCount(count);
        return row;
    }

    private TeamEntity team(Long id) {
        TeamEntity team = new TeamEntity();
        team.setId(id);
        team.setName("team");
        team.setOwnerUserId(TestIds.next());
        team.setStatus("ACTIVE");
        team.setAvatarUrl(AVATAR);
        return team;
    }

    private ProjectMembershipView project(Long teamId, String role) {
        ProjectMembershipView project = new ProjectMembershipView();
        project.setId(TestIds.next());
        project.setTeamId(teamId);
        project.setName("owner-project");
        project.setRole(role);
        project.setStatus("ACTIVE");
        return project;
    }
}

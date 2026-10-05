package qg.qgent.project.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import qg.qgent.notification.service.NotificationService;
import qg.qgent.project.dto.ProjectCreateRequest;
import qg.qgent.project.dto.ProjectUpdateRequest;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.mapper.view.ProjectMembershipView;
import qg.qgent.team.entity.TeamMemberEntity;
import java.util.List;
import qg.qgent.project.event.ProjectCreatedEvent;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.realtime.service.EventService;
import qg.qgent.team.entity.TeamEntity;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.team.mapper.TeamMemberMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectServiceTest {
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final ProjectMemberMapper projectMembers = mock(ProjectMemberMapper.class);
    private final TeamMapper teams = mock(TeamMapper.class);
    private final TeamMemberMapper teamMembers = mock(TeamMemberMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final ProjectService service = new ProjectService(projects, projectMembers, teams, teamMembers,
            access, mock(ApplicationEventPublisher.class), mock(NotificationService.class), mock(EventService.class));

    @Test
    void projectListReturnsAvatarAndMemberCountWithoutExtraQueries() {
        UUID actor = UUID.randomUUID(), teamId = UUID.randomUUID();
        TeamEntity team = new TeamEntity();
        team.setStatus("ACTIVE");
        when(teams.selectById(teamId)).thenReturn(team);
        when(teamMembers.selectByTeamAndUser(teamId, actor)).thenReturn(new TeamMemberEntity());
        ProjectMembershipView row = new ProjectMembershipView();
        row.setId(UUID.randomUUID());
        row.setTeamId(teamId);
        row.setAvatarUrl("https://assets.example/avatar.png");
        row.setMemberCount(3L);
        when(projects.selectAccessiblePage(teamId, actor, false, null, 21)).thenReturn(List.of(row));

        var response = service.list(actor, teamId, null, 20).getData().getFirst();

        assertThat(response.getAvatarUrl()).isEqualTo(row.getAvatarUrl());
        assertThat(response.getMemberCount()).isEqualTo(3L);
        verifyNoInteractions(projectMembers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unchanged", "clear", "replace", "archive", "restore"})
    void projectChangesPreserveReplaceOrClearAvatar(String operation) {
        UUID actor = UUID.randomUUID();
        ProjectEntity project = new ProjectEntity();
        project.setId(UUID.randomUUID());
        project.setTeamId(UUID.randomUUID());
        project.setName("项目");
        project.setStatus("restore".equals(operation) ? "ARCHIVED" : "ACTIVE");
        project.setAvatarUrl("https://assets.example/original.png");
        when(projects.selectByIdForUpdate(project.getId())).thenReturn(project);
        String expectedAvatar = project.getAvatarUrl();
        qg.qgent.project.dto.ProjectResponse result;
        if ("archive".equals(operation)) {
            result = service.archive(actor, project.getId());
        } else if ("restore".equals(operation)) {
            result = service.restore(actor, project.getId());
        } else {
            ProjectUpdateRequest request = new ProjectUpdateRequest();
            request.setName("更新名称");
            if ("clear".equals(operation)) {
                request.setAvatarUrl(" ");
                expectedAvatar = null;
            } else if ("replace".equals(operation)) {
                request.setAvatarUrl(" https://assets.example/new.png ");
                expectedAvatar = "https://assets.example/new.png";
            }
            result = service.update(actor, project.getId(), request);
        }

        assertThat(result.getAvatarUrl()).isEqualTo(expectedAvatar);
        assertThat(project.getAvatarUrl()).isEqualTo(expectedAvatar);
        verify(projects).updateById(project);
    }

    @Test
    void creationInitializesProjectAndChatWithoutRepositoryInputsOrExternalCalls() {
        UUID owner = UUID.randomUUID(), teamId = UUID.randomUUID();
        var teams = mock(TeamMapper.class);
        var team = new TeamEntity();
        team.setId(teamId);
        team.setOwnerUserId(owner);
        team.setStatus("ACTIVE");
        when(teams.selectByIdForUpdate(teamId)).thenReturn(team);
        var events = mock(ApplicationEventPublisher.class);
        var members = mock(TeamMemberMapper.class);
        var membership = new qg.qgent.team.entity.TeamMemberEntity();
        membership.setRole("TEAM_OWNER");
        when(members.selectByTeamAndUser(teamId, owner)).thenReturn(membership);
        var service = new ProjectService(mock(ProjectMapper.class), mock(ProjectMemberMapper.class), teams,
                members, mock(ProjectAccessService.class), events,
                mock(NotificationService.class), mock(EventService.class));
        var request = new ProjectCreateRequest();
        request.setName("项目");
        var created = service.create(owner, teamId, request);
        assertThat(created.getName()).isEqualTo("项目");
        verify(events).publishEvent(argThat((Object event) -> event instanceof ProjectCreatedEvent project
                && project.projectId().toString().equals(created.getId()) && project.creatorUserId().equals(owner)));
    }
}

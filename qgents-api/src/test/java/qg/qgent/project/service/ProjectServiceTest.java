package qg.qgent.project.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import qg.qgent.notification.service.NotificationService;
import qg.qgent.project.dto.ProjectCreateRequest;
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

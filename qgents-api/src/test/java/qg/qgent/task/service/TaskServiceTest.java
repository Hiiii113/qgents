package qg.qgent.task.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.chat.service.GroupService;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.task.dto.TaskCreateRequest;
import qg.qgent.task.mapper.TaskMapper;
import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.mapper.WorkspaceMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskServiceTest {
    @Test
    void taskGetsFixedEmptyWorkspaceWithoutRepositorySelection() {
        var tasks = mock(TaskMapper.class);
        var workspaces = mock(WorkspaceMapper.class);
        var groups = mock(GroupService.class);
        var service = new TaskService(tasks, workspaces, mock(ProjectMapper.class), groups,
                mock(ProjectAccessService.class), mock(EventService.class));
        UUID project = UUID.randomUUID(), actor = UUID.randomUUID(), group = UUID.randomUUID();
        var request = new TaskCreateRequest();
        request.setGroupId(group);
        request.setTitle("实现功能");
        request.setRequirement("由 Agent 选择仓库");
        var response = service.create(project, actor, request);
        var captor = ArgumentCaptor.forClass(WorkspaceEntity.class);
        verify(workspaces).insert(captor.capture());
        var workspace = captor.getValue();
        assertThat(workspace.getProjectId()).isEqualTo(project);
        assertThat(workspace.getStorageKey()).isEqualTo("workspaces/" + workspace.getId());
        assertThat(response.getWorkspaceId()).isEqualTo(workspace.getId().toString());
        assertThat(response.getStatus()).isEqualTo("PENDING");
        verify(groups).requireGroupMember(project, group, actor);
    }
}

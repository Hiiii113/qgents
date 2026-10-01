package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import qg.qgent.service.GroupService;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.service.ProjectAccessService;
import qg.qgent.service.EventService;
import qg.qgent.dto.TaskCreateRequest;
import qg.qgent.entity.TaskEntity;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.entity.WorkspaceEntity;
import qg.qgent.mapper.WorkspaceMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskServiceTest {
    @Test
    void eachNewTaskGetsFixedWorkspaceAndRepositoryBranches() {
        TaskMapper tasks = mock(TaskMapper.class);
        WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
        WorkspaceRepositoryMapper worktrees = mock(WorkspaceRepositoryMapper.class);
        ProjectRepositoryMapper repositories = mock(ProjectRepositoryMapper.class);
        TaskService service = new TaskService(tasks, workspaces, worktrees, repositories, mock(ProjectMapper.class),
                mock(GroupService.class), mock(ProjectAccessService.class), mock(EventService.class));
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId);
        repository.setProjectId(projectId);
        repository.setStatus("ACTIVE");
        when(repositories.selectById(repositoryId)).thenReturn(repository);
        when(worktrees.selectByWorkspace(any())).thenReturn(List.of());
        TaskCreateRequest request = new TaskCreateRequest();
        request.setRequirementGroupId(UUID.randomUUID());
        request.setTitle(" 修改代码 ");
        request.setRequirement(" 需求 ");
        request.setRepositoryIds(List.of(repositoryId));
        request.setBaseRefs(Map.of(repositoryId, "develop"));

        service.create(projectId, actor, request);
        service.create(projectId, actor, request);

        ArgumentCaptor<TaskEntity> taskRecords = ArgumentCaptor.forClass(TaskEntity.class);
        ArgumentCaptor<WorkspaceEntity> workspaceRecords = ArgumentCaptor.forClass(WorkspaceEntity.class);
        verify(tasks, times(2)).insert(taskRecords.capture());
        verify(workspaces, times(2)).insert(workspaceRecords.capture());
        TaskEntity first = taskRecords.getAllValues().getFirst();
        TaskEntity second = taskRecords.getAllValues().getLast();
        assertThat(first.getWorkspaceId()).isEqualTo(workspaceRecords.getAllValues().getFirst().getId());
        assertThat(second.getWorkspaceId()).isNotEqualTo(first.getWorkspaceId());
        assertThat(first.getTitle()).isEqualTo("修改代码");
        assertThat(first.getStatus()).isEqualTo("PENDING");
        verify(worktrees).insertLink(first.getWorkspaceId(), repositoryId, "repo-1", "develop", "feat/task-" + first.getId());
        verify(worktrees).insertLink(second.getWorkspaceId(), repositoryId, "repo-1", "develop", "feat/task-" + second.getId());
    }
}

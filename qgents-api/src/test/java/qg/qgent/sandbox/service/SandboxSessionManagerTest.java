package qg.qgent.sandbox.service;

import org.junit.jupiter.api.Test;
import qg.qgent.sandbox.client.SandboxWorkerClient;
import qg.qgent.sandbox.config.SandboxWorkerProperties;
import qg.qgent.sandbox.dto.WorkerCreateSandboxRequest;
import qg.qgent.sandbox.dto.WorkerSandbox;
import qg.qgent.sandbox.dto.WorkerWorkspace;
import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.mapper.WorkspaceMapper;
import qg.qgent.workspace.service.WorkspaceWriteLease;
import qg.qgent.workspace.service.WorkspaceWriteLeaseService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SandboxSessionManagerTest {
    private final SandboxWorkerClient client = mock(SandboxWorkerClient.class);
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final WorkspaceWriteLeaseService leases = mock(WorkspaceWriteLeaseService.class);
    private final SandboxSessionManager sessions = new SandboxSessionManager(client,
            new SandboxWorkerProperties(), workspaces, leases);
    private final UUID projectId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final WorkspaceWriteLease lease = mock(WorkspaceWriteLease.class);

    @Test
    void preparesAndReusesWorkerSandboxWithDefaultConfiguration() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setProjectId(projectId);
        workspace.setStatus("PROVISIONING");
        when(workspaces.selectById(workspaceId)).thenReturn(workspace);
        when(leases.acquire(projectId, workspaceId, runId)).thenReturn(lease);
        WorkerWorkspace ready = new WorkerWorkspace();
        ready.setStorageKey("workspaces/" + workspaceId);
        when(client.provisionWorkspace(eq(workspaceId), any())).thenReturn(ready);
        WorkerSandbox sandbox = new WorkerSandbox();
        sandbox.setId(UUID.randomUUID());
        when(client.createSandbox(any())).thenReturn(sandbox);

        SandboxSession session = sessions.acquire(runId, projectId, workspaceId);

        assertThat(session.sandboxId()).isEqualTo(sandbox.getId());
        assertThat(sessions.acquire(runId, projectId, workspaceId)).isSameAs(session);
        assertThat(workspace.getStatus()).isEqualTo("READY");
        verify(client).createSandbox(argThat((WorkerCreateSandboxRequest request) ->
                runId.equals(request.getAgentRunId()) && ready.getStorageKey().equals(request.getWorkspaceStorageKey())));
        verify(workspaces).updateById(workspace);
    }

    @Test
    void workerFailurePropagatesAndReleasesWriteLease() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setProjectId(projectId);
        when(workspaces.selectById(workspaceId)).thenReturn(workspace);
        when(leases.acquire(projectId, workspaceId, runId)).thenReturn(lease);
        IllegalStateException failure = new IllegalStateException("Worker unavailable");
        when(client.provisionWorkspace(eq(workspaceId), any())).thenThrow(failure);

        assertThatThrownBy(() -> sessions.acquire(runId, projectId, workspaceId)).isSameAs(failure);

        verify(leases).release(lease);
        verify(client, never()).createSandbox(any());
        verify(workspaces, never()).updateById(any(WorkspaceEntity.class));
        assertThatThrownBy(() -> sessions.require(workspaceId)).isInstanceOf(IllegalStateException.class);
    }
}

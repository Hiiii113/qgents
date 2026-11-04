package qg.qgent.sandbox.service;

import org.junit.jupiter.api.Test;
import qg.qgent.workspace.service.WorkspaceStorageService;
import qg.qgent.sandbox.execution.service.ToolExecutionService;
import qg.qgent.sandbox.config.SandboxProperties;
import qg.qgent.sandbox.dto.CreateSandboxRequest;
import qg.qgent.sandbox.dto.SandboxResponse;
import qg.qgent.workspace.dto.WorkspaceResponse;
import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.mapper.WorkspaceMapper;
import qg.qgent.workspace.service.WorkspaceWriteLease;
import qg.qgent.workspace.service.WorkspaceWriteLeaseService;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SandboxSessionManagerTest {
    private final WorkspaceStorageService storage = mock(WorkspaceStorageService.class);
    private final SandboxService sandboxes = mock(SandboxService.class);
    private final ToolExecutionService executions = mock(ToolExecutionService.class);
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final WorkspaceWriteLeaseService leases = mock(WorkspaceWriteLeaseService.class);
    private final SandboxSessionManager sessions = new SandboxSessionManager(sandboxes, storage, executions,
            new SandboxProperties(), workspaces, leases);
    private final UUID projectId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final WorkspaceWriteLease lease = mock(WorkspaceWriteLease.class);

    @Test
    void preparesAndReusesSandboxResponseWithDefaultConfiguration() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setProjectId(projectId);
        workspace.setStatus("PROVISIONING");
        when(workspaces.selectById(workspaceId)).thenReturn(workspace);
        when(leases.acquire(projectId, workspaceId, runId)).thenReturn(lease);
        WorkspaceResponse ready = new WorkspaceResponse();
        ready.setStorageKey("workspaces/" + workspaceId);
        when(storage.provision(eq(workspaceId), any())).thenReturn(ready);
        SandboxResponse sandbox = new SandboxResponse();
        sandbox.setId(UUID.randomUUID());
        when(sandboxes.create(any())).thenReturn(sandbox);

        SandboxSession session = sessions.acquire(runId, projectId, workspaceId);

        assertThat(session.sandboxId()).isEqualTo(sandbox.getId());
        assertThat(sessions.acquire(runId, projectId, workspaceId)).isSameAs(session);
        assertThat(workspace.getStatus()).isEqualTo("READY");
        verify(sandboxes).create(argThat((CreateSandboxRequest request) ->
                runId.equals(request.getAgentRunId()) && ready.getStorageKey().equals(request.getWorkspaceStorageKey())));
        verify(workspaces).updateById(workspace);
    }

    @Test
    void sandboxFailurePropagatesAndReleasesWriteLease() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setProjectId(projectId);
        when(workspaces.selectById(workspaceId)).thenReturn(workspace);
        when(leases.acquire(projectId, workspaceId, runId)).thenReturn(lease);
        IllegalStateException failure = new IllegalStateException("Sandbox unavailable");
        when(storage.provision(eq(workspaceId), any())).thenThrow(failure);

        assertThatThrownBy(() -> sessions.acquire(runId, projectId, workspaceId)).isSameAs(failure);

        verify(leases).release(lease);
        verify(sandboxes, never()).create(any());
        verify(workspaces, never()).updateById(any(WorkspaceEntity.class));
        assertThatThrownBy(() -> sessions.require(workspaceId)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void releasesWorkspaceOnlyAfterCommandWorkersExit() throws Exception {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setProjectId(projectId);
        workspace.setStatus("PROVISIONING");
        when(lease.getAgentRunId()).thenReturn(runId);
        when(workspaces.selectById(workspaceId)).thenReturn(workspace);
        when(leases.acquire(projectId, workspaceId, runId)).thenReturn(lease);
        WorkspaceResponse ready = new WorkspaceResponse();
        ready.setStorageKey("workspaces/" + workspaceId);
        when(storage.provision(eq(workspaceId), any())).thenReturn(ready);
        SandboxResponse sandbox = new SandboxResponse();
        sandbox.setId(UUID.randomUUID());
        when(sandboxes.create(any())).thenReturn(sandbox);
        sessions.acquire(runId, projectId, workspaceId);
        CountDownLatch waitingForCommands = new CountDownLatch(1), releaseCommands = new CountDownLatch(1);
        doAnswer(invocation -> {
            waitingForCommands.countDown();
            assertThat(releaseCommands.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(executions).stopAndAwaitBySandbox(sandbox.getId());

        try (var executor = Executors.newSingleThreadExecutor()) {
            var release = executor.submit(() -> sessions.release(workspaceId, runId));
            assertThat(waitingForCommands.await(5, TimeUnit.SECONDS)).isTrue();
            verify(sandboxes, never()).destroy(any());
            verify(leases, never()).release(lease);
            releaseCommands.countDown();
            release.get(5, TimeUnit.SECONDS);
        } finally {
            releaseCommands.countDown();
        }

        verify(sandboxes).destroy(sandbox.getId());
        verify(leases).release(lease);
    }
}

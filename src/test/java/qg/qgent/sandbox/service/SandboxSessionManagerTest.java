package qg.qgent.sandbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.sandbox.config.SandboxProperties;
import qg.qgent.sandbox.execution.service.CommandExecutionService;
import qg.qgent.sandbox.runtime.SandboxAllocation;
import qg.qgent.support.TestIds;
import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.mapper.WorkspaceMapper;
import qg.qgent.workspace.service.WorkspaceService;

class SandboxSessionManagerTest {
    private final ProjectDirectoryService directories = mock(ProjectDirectoryService.class);
    private final SandboxService sandboxes = mock(SandboxService.class);
    private final CommandExecutionService executions = mock(CommandExecutionService.class);
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final SandboxSessionManager sessions =
        new SandboxSessionManager(
            sandboxes, directories, executions, new SandboxProperties(), workspaces);
    private final Long projectId = TestIds.next();
    private final Long workspaceId = TestIds.next();
    private final Long runId = TestIds.next();

    @Test
    void preparesAndReusesSandboxWithDefaultConfiguration() {
        WorkspaceEntity workspace = workspace();
        SandboxAllocation sandbox = allocation();
        when(sandboxes.create(any(), any(), anyString(), anyString())).thenReturn(sandbox);

        SandboxSession session = sessions.acquire(runId, projectId, workspaceId);

        assertThat(session.sandboxId()).isEqualTo(sandbox.getId());
        assertThat(sessions.acquire(runId, projectId, workspaceId)).isSameAs(session);
        verify(sandboxes)
            .create(
                any(UUID.class),
                eq(runId),
                eq(WorkspaceService.storageKey(workspace)),
                eq("dev-tools"));
        // Workspace 记录只保留持久元数据，准备过程不再回写。
        verify(workspaces, never()).updateById(any(WorkspaceEntity.class));
    }

    @Test
    void anotherAgentRunIsRejectedWhileWorkspaceIsInUse() {
        workspace();
        when(sandboxes.create(any(), any(), anyString(), anyString())).thenReturn(allocation());
        SandboxSession session = sessions.acquire(runId, projectId, workspaceId);

        assertThatThrownBy(() -> sessions.acquire(TestIds.next(), projectId, workspaceId))
            .isInstanceOfSatisfying(
                ApiException.class,
                failure -> {
                    assertThat(failure.status().value()).isEqualTo(409);
                    assertThat(failure.code()).isEqualTo("WORKSPACE_WRITE_LEASE_HELD");
                });
        assertThat(sessions.require(workspaceId)).isSameAs(session);
        verify(sandboxes, times(1)).create(any(), any(), anyString(), anyString());
    }

    @Test
    void sandboxFailurePropagatesWithoutCachingSession() {
        workspace();
        IllegalStateException failure = new IllegalStateException("Sandbox unavailable");
        when(sandboxes.create(any(), any(), anyString(), anyString())).thenThrow(failure);

        assertThatThrownBy(() -> sessions.acquire(runId, projectId, workspaceId)).isSameAs(failure);

        verify(workspaces, never()).updateById(any(WorkspaceEntity.class));
        assertThatThrownBy(() -> sessions.require(workspaceId))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void releasesWorkspaceOnlyAfterCommandWorkersExit() throws Exception {
        workspace();
        SandboxAllocation sandbox = allocation();
        when(sandboxes.create(any(), any(), anyString(), anyString())).thenReturn(sandbox);
        sessions.acquire(runId, projectId, workspaceId);
        CountDownLatch waitingForCommands = new CountDownLatch(1),
            releaseCommands = new CountDownLatch(1);
        doAnswer(
                invocation -> {
                    waitingForCommands.countDown();
                    assertThat(releaseCommands.await(5, TimeUnit.SECONDS)).isTrue();
                    return null;
                })
            .when(executions)
            .stopAndAwaitBySandbox(sandbox.getId());

        try (var executor = Executors.newSingleThreadExecutor()) {
            var release = executor.submit(() -> sessions.release(workspaceId, runId));
            assertThat(waitingForCommands.await(5, TimeUnit.SECONDS)).isTrue();
            verify(sandboxes, never()).destroy(any());
            releaseCommands.countDown();
            release.get(5, TimeUnit.SECONDS);
        } finally {
            releaseCommands.countDown();
        }

        verify(sandboxes).destroy(sandbox.getId());
        assertThatThrownBy(() -> sessions.require(workspaceId))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void otherAgentRunCannotReleaseSomeoneElsesSession() {
        workspace();
        SandboxAllocation sandbox = allocation();
        when(sandboxes.create(any(), any(), anyString(), anyString())).thenReturn(sandbox);
        sessions.acquire(runId, projectId, workspaceId);

        sessions.release(workspaceId, TestIds.next());

        assertThat(sessions.require(workspaceId).agentRunId()).isEqualTo(runId);
        verify(sandboxes, never()).destroy(any());
    }

    @Test
    void cleanupForgetsDestroyedSandboxSoWorkspaceCanBeReacquired() {
        workspace();
        SandboxAllocation first = allocation();
        SandboxAllocation second = allocation();
        when(sandboxes.create(any(), any(), anyString(), anyString()))
            .thenReturn(first, second);
        SandboxSession session = sessions.acquire(runId, projectId, workspaceId);

        sessions.forgetSandbox(session.sandboxId());

        assertThatThrownBy(() -> sessions.require(workspaceId))
            .isInstanceOf(IllegalStateException.class);
        assertThat(sessions.acquire(runId, projectId, workspaceId).sandboxId())
            .isEqualTo(second.getId());
    }

    @Test
    void forgettingUnrelatedSandboxKeepsActiveSession() {
        workspace();
        SandboxAllocation sandbox = allocation();
        when(sandboxes.create(any(), any(), anyString(), anyString())).thenReturn(sandbox);
        SandboxSession session = sessions.acquire(runId, projectId, workspaceId);

        sessions.forgetSandbox(UUID.randomUUID());

        assertThat(sessions.require(workspaceId)).isSameAs(session);
    }

    private WorkspaceEntity workspace() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setProjectId(projectId);
        workspace.setIsDefault(true);
        when(workspaces.selectById(workspaceId)).thenReturn(workspace);
        return workspace;
    }

    private SandboxAllocation allocation() {
        Instant now = Instant.now();
        return new SandboxAllocation(
            UUID.randomUUID(),
            runId,
            projectId + "/workspaces/default",
            "dev-tools",
            "READY",
            "FAKE",
            now,
            now,
            now.plus(Duration.ofMinutes(30)),
            now.plus(Duration.ofHours(4)),
            Duration.ofMinutes(15),
            null);
    }
}

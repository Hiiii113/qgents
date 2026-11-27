package qg.qgent.sandbox.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.sandbox.config.SandboxProperties;
import qg.qgent.sandbox.execution.service.CommandExecutionService;
import qg.qgent.sandbox.runtime.SandboxAllocation;
import qg.qgent.sandbox.service.SandboxCleanupService;
import qg.qgent.sandbox.service.SandboxService;
import qg.qgent.sandbox.service.SandboxSessionManager;
import qg.qgent.support.TestIds;
import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.mapper.WorkspaceMapper;

class SandboxMetricsTest {

    @Test
    void recordsSessionLifecycleLeaseConflictAndExpiredCleanup() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SandboxMetrics metrics = new SandboxMetrics(registry);
        ProjectDirectoryService directories = mock(ProjectDirectoryService.class);
        SandboxService sandboxes = mock(SandboxService.class);
        CommandExecutionService executions = mock(CommandExecutionService.class);
        WorkspaceMapper workspaces = mock(WorkspaceMapper.class);

        SandboxSessionManager sessions =
            new SandboxSessionManager(
                sandboxes, directories, executions, new SandboxProperties(), workspaces, metrics);
        SandboxCleanupService cleanup =
            new SandboxCleanupService(sandboxes, executions, sessions, Runnable::run, metrics);

        Long projectId = TestIds.next();
        Long workspaceId = TestIds.next();
        Long runId = TestIds.next();
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setProjectId(projectId);
        workspace.setIsDefault(true);
        when(workspaces.selectById(workspaceId)).thenReturn(workspace);

        UUID sandboxId = UUID.randomUUID();
        Instant now = Instant.now();
        SandboxAllocation allocation =
            new SandboxAllocation(
                sandboxId,
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
        when(sandboxes.create(any(), any(), anyString(), anyString())).thenReturn(allocation);

        assertThat(registry.get("qgents.sandbox.sessions.active").gauge().value()).isEqualTo(0.0);
        sessions.acquire(runId, projectId, workspaceId);
        assertThat(registry.get("qgents.sandbox.sessions.active").gauge().value()).isEqualTo(1.0);
        sessions.acquire(runId, projectId, workspaceId);
        assertThatThrownBy(() -> sessions.acquire(TestIds.next(), projectId, workspaceId))
            .isInstanceOf(ApiException.class);

        sessions.release(workspaceId, runId);
        assertThat(registry.get("qgents.sandbox.sessions.active").gauge().value()).isEqualTo(0.0);

        UUID expiredId = UUID.randomUUID();
        when(sandboxes.expiredSandboxIds()).thenReturn(List.of(expiredId));
        cleanup.cleanupExpiredSandboxes();

        assertThat(registry.get("qgents.sandbox.acquire.duration").tag("outcome", "created").timer().count())
            .isEqualTo(1);
        assertThat(registry.get("qgents.sandbox.acquire.duration").tag("outcome", "reused").timer().count())
            .isEqualTo(1);
        assertThat(registry.get("qgents.sandbox.acquire.duration").tag("outcome", "lease_conflict").timer().count())
            .isEqualTo(1);
        assertThat(registry.get("qgents.sandbox.reclaimed.total").tag("reason", "release").counter().count())
            .isEqualTo(1.0);
        assertThat(registry.get("qgents.sandbox.reclaimed.total").tag("reason", "expired_cleanup").counter().count())
            .isEqualTo(1.0);
    }
}

package qg.qgent.sandboxworker.sandbox.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import qg.qgent.sandboxworker.common.web.WorkerException;
import qg.qgent.sandboxworker.sandbox.config.SandboxWorkerProperties;
import qg.qgent.sandboxworker.sandbox.dto.CreateSandboxRequest;
import qg.qgent.sandboxworker.sandbox.dto.ResourceLimitsRequest;
import qg.qgent.sandboxworker.sandbox.dto.SandboxResponse;
import qg.qgent.sandboxworker.sandbox.runtime.FakeContainerRuntime;
import qg.qgent.sandboxworker.workspace.service.WorkspaceOperationLock;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SandboxServiceTest {
    @TempDir
    Path temporaryDirectory;

    private final Instant now = Instant.parse("2026-08-12T00:00:00Z");
    private final FakeContainerRuntime runtime = new FakeContainerRuntime();

    @Test
    void createClampsRequestedTtlToWorkerLimits() {
        CreateSandboxRequest request = request();
        ResourceLimitsRequest limits = new ResourceLimitsRequest();
        limits.setIdleTtlSeconds(9999L);
        limits.setMaxLifetimeSeconds(9999L);
        request.setLimits(limits);

        SandboxResponse response = service().create(request);

        assertEquals(now.plus(Duration.ofMinutes(10)), response.getExpiresAt());
        assertEquals(now.plus(Duration.ofHours(1)), response.getMaxExpiresAt());
    }

    @Test
    void expiredSandboxIsSelectedForCleanup() {
        CreateSandboxRequest request = request();
        ResourceLimitsRequest limits = new ResourceLimitsRequest();
        limits.setIdleTtlSeconds(1L);
        request.setLimits(limits);
        SandboxService service = service();
        service.create(request);
        runtime.find(request.getSandboxId()).orElseThrow().setExpiresAt(now.minusSeconds(1));

        assertTrue(service.expiredSandboxIds().contains(request.getSandboxId()));
    }





    @Test
    void sameSandboxIdSameSpecIsIdempotent() {
        SandboxService service = service();
        CreateSandboxRequest request = request();

        SandboxResponse first = service.create(request);
        SandboxResponse second = service.create(request);

        assertEquals(first.getId(), second.getId());
        assertEquals(1, runtime.findAll().size());
    }

    @Test
    void responseContainsSandboxSpecNeededForIdempotentRecovery() {
        CreateSandboxRequest request = request();

        SandboxResponse response = service().create(request);

        assertEquals(request.getWorkspaceStorageKey(), response.getWorkspaceStorageKey());
        assertEquals(request.getTaskId(), response.getTaskId());
        assertEquals(request.getImageProfile(), response.getImageProfile());
    }

    @Test
    void sameSandboxIdDifferentSpecConflicts() {
        SandboxService service = service();
        CreateSandboxRequest request = request();
        service.create(request);

        CreateSandboxRequest conflict = request();
        conflict.setSandboxId(request.getSandboxId());
        conflict.setTaskId(UUID.randomUUID());

        var exception = assertThrows(qg.qgent.sandboxworker.common.web.WorkerException.class,
                () -> service.create(conflict));

        assertEquals("SANDBOX_ID_CONFLICT", exception.getCode());
    }

    private CreateSandboxRequest request() {
        CreateSandboxRequest request = new CreateSandboxRequest();
        request.setSandboxId(UUID.randomUUID());
        request.setTaskId(UUID.randomUUID());
        request.setTaskId(UUID.randomUUID());
        request.setWorkspaceStorageKey("workspaces/" + UUID.randomUUID());
        request.setImageProfile("dev-tools");
        return request;
    }

    private SandboxWorkerProperties properties() {
        SandboxWorkerProperties value = new SandboxWorkerProperties();
        value.setWorkspaceMetadataRoot(temporaryDirectory.resolve("metadata").toString());
        value.setMaxIdleTtl(Duration.ofMinutes(10));
        value.setMaxLifetime(Duration.ofHours(1));
        return value;
    }

    private SandboxService service() {
        SandboxWorkerProperties properties = properties();
        return new SandboxService(runtime, properties, Clock.fixed(now, ZoneOffset.UTC),
                new WorkspaceOperationLock(properties));
    }
}

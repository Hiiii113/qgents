package qg.qgent.sandbox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandbox.config.SandboxProperties;
import qg.qgent.sandbox.runtime.FakeContainerRuntime;
import qg.qgent.sandbox.runtime.SandboxAllocation;
import qg.qgent.support.TestIds;
import qg.qgent.workspace.service.WorkspaceOperationLock;

class SandboxServiceTest {
    @TempDir Path temporaryDirectory;

    private final Instant now = Instant.parse("2026-08-12T00:00:00Z");
    private final FakeContainerRuntime runtime = new FakeContainerRuntime();

    @Test
    void createUsesConfiguredLimitsInsteadOfCallerValues() {
        SandboxAllocation allocation = service().create(sandboxId(), TestIds.next(), storageKey(), "dev-tools");

        assertEquals(now.plus(Duration.ofMinutes(10)), allocation.getExpiresAt());
        assertEquals(now.plus(Duration.ofHours(1)), allocation.getMaxExpiresAt());
        assertEquals(Duration.ofMinutes(15), allocation.getExecutionTimeout());
    }

    @Test
    void expiredSandboxIsSelectedForCleanup() {
        UUID sandboxId = sandboxId();
        SandboxService service = service();
        service.create(sandboxId, TestIds.next(), storageKey(), "dev-tools");
        runtime.find(sandboxId).orElseThrow().setExpiresAt(now.minusSeconds(1));

        assertTrue(service.expiredSandboxIds().contains(sandboxId));
    }

    @Test
    void sameSandboxIdSameSpecIsIdempotent() {
        UUID sandboxId = sandboxId();
        Long agentRunId = TestIds.next();
        String storageKey = storageKey();
        SandboxService service = service();

        SandboxAllocation first = service.create(sandboxId, agentRunId, storageKey, "dev-tools");
        SandboxAllocation second = service.create(sandboxId, agentRunId, storageKey, "dev-tools");

        assertEquals(first.getId(), second.getId());
        assertEquals(1, runtime.findAll().size());
    }

    @Test
    void allocationContainsSandboxSpecNeededForIdempotentRecovery() {
        UUID sandboxId = sandboxId();
        Long agentRunId = TestIds.next();
        String storageKey = storageKey();

        SandboxAllocation allocation =
            service().create(sandboxId, agentRunId, storageKey, "dev-tools");

        assertEquals(storageKey, allocation.getWorkspaceStorageKey());
        assertEquals(agentRunId, allocation.getAgentRunId());
        assertEquals("dev-tools", allocation.getImageProfile());
    }

    @Test
    void sameSandboxIdDifferentSpecConflicts() {
        UUID sandboxId = sandboxId();
        String storageKey = storageKey();
        SandboxService service = service();
        service.create(sandboxId, TestIds.next(), storageKey, "dev-tools");

        var exception =
            assertThrows(
                qg.qgent.sandbox.exception.SandboxException.class,
                () -> service.create(sandboxId, TestIds.next(), storageKey, "dev-tools"));

        assertEquals("SANDBOX_ID_CONFLICT", exception.getCode());
    }

    @Test
    void imageProfileMustBeConfigured() {
        var exception =
            assertThrows(
                qg.qgent.sandbox.exception.SandboxException.class,
                () -> service().create(sandboxId(), TestIds.next(), storageKey(), "unknown"));

        assertEquals("IMAGE_PROFILE_NOT_ALLOWED", exception.getCode());
    }

    private UUID sandboxId() {
        return UUID.randomUUID();
    }

    private String storageKey() {
        return TestIds.next() + "/workspaces/default";
    }

    private SandboxProperties properties() {
        SandboxProperties value = new SandboxProperties();
        value.setWorkspaceMetadataRoot(temporaryDirectory.resolve("metadata").toString());
        value.setMaxIdleTtl(Duration.ofMinutes(10));
        value.setMaxLifetime(Duration.ofHours(1));
        return value;
    }

    private SandboxService service() {
        SandboxProperties properties = properties();
        return new SandboxService(
            runtime,
            properties,
            Clock.fixed(now, ZoneOffset.UTC),
            new WorkspaceOperationLock(properties));
    }
}

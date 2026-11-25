package qg.qgent.sandbox.runtime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import qg.qgent.support.TestIds;

class FakeContainerRuntimeTest {
    private final FakeContainerRuntime runtime = new FakeContainerRuntime();

    @Test
    void duplicateSandboxIdIsRejected() {
        SandboxAllocation allocation = allocation();
        runtime.create(allocation);

        assertThrows(IllegalStateException.class, () -> runtime.create(allocation));
    }

    @Test
    void destroyIsIdempotent() {
        SandboxAllocation allocation = allocation();
        runtime.create(allocation);

        runtime.destroy(allocation.getId());
        runtime.destroy(allocation.getId());

        assertTrue(runtime.find(allocation.getId()).isEmpty());
    }

    @Test
    void workspaceUseTracksRegisteredSandbox() {
        SandboxAllocation allocation = allocation();
        runtime.create(allocation);

        assertTrue(runtime.isWorkspaceInUse(allocation.getWorkspaceStorageKey()));
    }

    private SandboxAllocation allocation() {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        return new SandboxAllocation(
                java.util.UUID.randomUUID(),
                TestIds.next(),
                TestIds.next() + "/workspaces/default",
                "dev-tools",
                "READY",
                "FAKE",
                now,
                now,
                now.plusSeconds(60),
                now.plusSeconds(3600),
                Duration.ofMinutes(15),
                null);
    }
}

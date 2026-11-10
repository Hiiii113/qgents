package qg.qgent.sandbox.runtime;

import qg.qgent.support.TestIds;

import org.junit.jupiter.api.Test;

import qg.qgent.sandbox.dto.CreateSandboxRequest;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeContainerRuntimeTest {
    private final FakeContainerRuntime runtime = new FakeContainerRuntime();

    @Test
    void duplicateSandboxIdIsRejected() {
        CreateSandboxRequest request = request();
        SandboxAllocation allocation = allocation(request);
        runtime.create(request, allocation);

        assertThrows(IllegalStateException.class, () -> runtime.create(request, allocation));
    }

    @Test
    void destroyIsIdempotent() {
        CreateSandboxRequest request = request();
        runtime.create(request, allocation(request));

        runtime.destroy(request.getSandboxId());
        runtime.destroy(request.getSandboxId());

        assertTrue(runtime.find(request.getSandboxId()).isEmpty());
    }

    private CreateSandboxRequest request() {
        CreateSandboxRequest request = new CreateSandboxRequest();
        request.setSandboxId(java.util.UUID.randomUUID());
        request.setAgentRunId(TestIds.next());
        request.setWorkspaceStorageKey("workspaces/example");
        request.setImageProfile("dev-tools");
        return request;
    }

    private SandboxAllocation allocation(CreateSandboxRequest request) {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        return new SandboxAllocation(request.getSandboxId(), request.getAgentRunId(), request.getWorkspaceStorageKey(),
                request.getImageProfile(), "READY", "FAKE", now, now, now.plusSeconds(60),
                now.plusSeconds(3600), Duration.ofMinutes(15), null);
    }
}

package qg.qgent.sandboxworker.workspace.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandboxworker.sandbox.config.SandboxWorkerProperties;
import qg.qgent.sandboxworker.sandbox.service.SandboxService;
import qg.qgent.sandboxworker.workspace.dto.WorkspaceProvisionRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceManagerServiceTest {
    @TempDir Path root;

    @Test
    void provisioningCreatesEmptyDirectoryAndReusePreservesAgentFiles() throws Exception {
        var properties = new SandboxWorkerProperties();
        properties.setWorkspaceLocalRoot(root.toString());
        var service = new WorkspaceManagerService(properties, new ObjectMapper(), Clock.systemUTC(),
                new WorkspaceOperationLock(properties), mock(SandboxService.class));
        var request = new WorkspaceProvisionRequest();
        request.setProjectId(UUID.randomUUID());
        UUID id = UUID.randomUUID();
        var workspace = service.provision(id, request);
        try (var entries = Files.list(root.resolve(id.toString()))) { assertThat(entries).isEmpty(); }
        Files.writeString(root.resolve(id.toString()).resolve("agent.txt"), "keep");
        assertThat(service.provision(id, request).getStorageKey()).isEqualTo(workspace.getStorageKey());
        assertThat(Files.readString(root.resolve(id.toString()).resolve("agent.txt"))).isEqualTo("keep");
        request.setProjectId(UUID.randomUUID());
        assertThatThrownBy(() -> service.provision(id, request)).hasMessage("工作区属于其他项目");
        service.delete(id);
        assertThat(root.resolve(id.toString())).doesNotExist();
    }
}

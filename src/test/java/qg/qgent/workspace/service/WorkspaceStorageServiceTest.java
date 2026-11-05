package qg.qgent.workspace.service;

import qg.qgent.support.TestIds;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandbox.config.SandboxProperties;
import qg.qgent.sandbox.service.SandboxService;
import qg.qgent.workspace.dto.WorkspaceProvisionRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceStorageServiceTest {
    @TempDir Path root;

    @Test
    void provisioningCreatesEmptyDirectoryAndReusePreservesAgentFiles() throws Exception {
        var properties = new SandboxProperties();
        properties.setWorkspaceLocalRoot(root.toString());
        var service = new WorkspaceStorageService(properties, new ObjectMapper(), Clock.systemUTC(),
                new WorkspaceOperationLock(properties), mock(SandboxService.class));
        var request = new WorkspaceProvisionRequest();
        request.setProjectId(TestIds.next());
        Long id = TestIds.next();
        String storageKey = "workspaces/" + UUID.randomUUID();
        Path directory = root.resolve(storageKey.substring("workspaces/".length()));
        var workspace = service.provision(id, storageKey, request);
        try (var entries = Files.list(directory)) { assertThat(entries).isEmpty(); }
        Files.writeString(directory.resolve("agent.txt"), "keep");
        assertThat(service.provision(id, storageKey, request).getStorageKey()).isEqualTo(workspace.getStorageKey());
        assertThat(Files.readString(directory.resolve("agent.txt"))).isEqualTo("keep");
        request.setProjectId(TestIds.next());
        assertThatThrownBy(() -> service.provision(id, storageKey, request)).hasMessage("工作区属于其他项目");
        service.delete(storageKey);
        assertThat(directory).doesNotExist();
    }
}

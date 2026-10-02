package qg.qgent.sandbox.runtime;

import com.github.dockerjava.api.model.AccessMode;
import org.junit.jupiter.api.Test;
import qg.qgent.sandbox.filesystem.service.WorkspacePathResolver;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SandboxBindFactoryTest {
    @Test
    void mountsEntireWorkspaceAsOneWritableDirectory() {
        var paths = mock(WorkspacePathResolver.class);
        var allocation = mock(SandboxAllocation.class);
        when(allocation.getWorkspaceStorageKey()).thenReturn("workspaces/id");
        when(paths.resolveDockerHost("workspaces/id")).thenReturn(Path.of("/data/id"));
        var binds = new SandboxBindFactory(paths).create(allocation);
        assertThat(binds).hasSize(1);
        assertThat(binds.getFirst().getVolume().getPath()).isEqualTo("/workspace");
        assertThat(binds.getFirst().getAccessMode()).isEqualTo(AccessMode.rw);
    }
}

package qg.qgent.workspace.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalWorkspaceCommandsTest {
    @TempDir Path root;

    @Test
    void agentCanCloneDefaultBranchCreateBranchAndCommitWithinSameWorkspace() throws Exception {
        UUID id = UUID.randomUUID();
        var resolver = mock(WorkspaceService.class);
        when(resolver.resolve(id)).thenReturn(new WorkspaceService.WorkspaceResolution(true, true, root, null));
        var commands = new LocalWorkspaceCommands(resolver);
        Files.createDirectory(root.resolve("source"));
        assertSuccess(commands, id, "source", List.of("git", "init", "--initial-branch=base"));
        Files.writeString(root.resolve("source/readme.txt"), "first");
        assertSuccess(commands, id, "source", List.of("git", "add", "."));
        assertSuccess(commands, id, "source", List.of("git", "-c", "user.name=Agent", "-c", "user.email=agent@example.test", "commit", "-m", "initial"));
        assertSuccess(commands, id, ".", List.of("git", "clone", "source", "selected"));
        assertThat(commands.run(id, "selected", List.of("git", "branch", "--show-current"), Map.of()).get("stdout")).isEqualTo("base\n");
        assertSuccess(commands, id, "selected", List.of("git", "switch", "-c", "feat/change"));
        Files.writeString(root.resolve("selected/readme.txt"), "changed");
        assertSuccess(commands, id, "selected", List.of("git", "add", "."));
        assertSuccess(commands, id, "selected", List.of("git", "-c", "user.name=Agent", "-c", "user.email=agent@example.test", "commit", "-m", "change"));
        assertThat(commands.run(id, "selected", List.of("git", "status", "--porcelain"), Map.of()).get("stdout")).isEqualTo("");
        var failed = commands.run(id, "selected", List.of("git", "rev-parse", "missing-ref"), Map.of());
        assertThat(failed.get("ok")).isEqualTo(false);
        assertThat(failed.get("stderr").toString()).contains("unknown revision");
        assertThatThrownBy(() -> commands.run(id, "..", List.of("git", "status"), Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    private void assertSuccess(LocalWorkspaceCommands commands, UUID id, String directory, List<String> argv) {
        assertThat(commands.run(id, directory, argv, Map.of()).get("exitCode")).isEqualTo(0);
    }
}

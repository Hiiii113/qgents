package qg.qgent.sandbox.execution.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandbox.execution.output.CommandOutput;
import qg.qgent.sandbox.runtime.CommandExecutionResult;
import qg.qgent.sandbox.runtime.CommandExecutor;
import qg.qgent.sandbox.runtime.FakeCommandExecutor;
import qg.qgent.support.TestIds;

class CommandRunToolTest {
    @TempDir Path root;

    @Test
    void selectedDirectoryOutputAndNonzeroExitAreReturnedWithTemporaryAuthentication()
        throws Exception {
        Files.createDirectory(root.resolve("selected"));
        var executor = mock(CommandExecutor.class);
        Long executionId = TestIds.next();
        Map<String, String> environment =
            Map.of("GIT_CONFIG_COUNT", "1", "GIT_CONFIG_VALUE_0", "very-secret-value");
        when(executor.execute(any(), anyString(), anyList(), any(), anyMap()))
            .thenReturn(
                new CommandExecutionResult(
                    17, List.of("101 results"), List.of("failed very-secret-value")));
        Duration timeout = Duration.ofSeconds(10);
        var outcome =
            new CommandRunTool(executor)
                .run(
                    null,
                    root,
                    "selected",
                    List.of("git", "status"),
                    timeout,
                    environment,
                    executionId);
        assertThat(outcome.exitCode()).isEqualTo(17);
        assertThat(outcome.stdout().toMap()).containsEntry("text", "101 results");
        assertThat(outcome.stderr().toMap()).containsEntry("text", "failed [redacted]");
        Path output = CommandOutput.directory(root, executionId);
        assertThat(Files.readString(output.resolve("stderr")))
            .isEqualTo("failed [redacted]")
            .doesNotContain("very-secret-value");
        verify(executor)
            .execute(
                null,
                "/workspace/selected",
                List.of("git", "status"),
                timeout,
                environment);
        assertThatThrownBy(() ->
                new CommandRunTool(executor)
                    .run(
                        null,
                        root,
                        "..",
                        List.of("git", "status"),
                        timeout,
                        environment,
                        executionId))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonStreamingRuntimeUsesTheSameOutputWindows() throws Exception {
        Long executionId = TestIds.next();
        Map<String, String> environment = Map.of();
        var outcome =
            new CommandRunTool(new FakeCommandExecutor())
                .run(
                    null,
                    root,
                    ".",
                    List.of("echo", "hello"),
                    Duration.ofSeconds(10),
                    environment,
                    executionId);

        Path output = CommandOutput.directory(root, executionId);
        String stdout = Files.readString(output.resolve("stdout"));
        assertThat(stdout).contains("echo hello");
        assertThat(CommandOutput.read(output, "stdout", 0, 16000).text()).isEqualTo(stdout);
        assertThat(outcome.toMap()).containsEntry("outputAvailable", true);
    }

    @Test
    void emptyCommandAndDefaultDirectoryAreHandled() throws Exception {
        var tool = new CommandRunTool(new FakeCommandExecutor());

        assertThatThrownBy(() ->
                tool.run(null, root, ".", List.of(), Duration.ofSeconds(5), Map.of(), 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("命令不能为空");
        assertThat(
                tool.run(
                        null,
                        root,
                        null,
                        List.of("echo", "hi"),
                        Duration.ofSeconds(5),
                        null,
                        2L)
                    .stdout()
                    .text())
            .contains("echo hi");
    }
}

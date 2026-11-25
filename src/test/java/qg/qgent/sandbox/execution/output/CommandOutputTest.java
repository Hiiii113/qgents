package qg.qgent.sandbox.execution.output;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandOutputTest {
    @TempDir Path root;

    @Test
    void readsHugeSingleLineWithoutLosingRemainingBytes() throws Exception {
        Files.writeString(root.resolve("stdout"), "x".repeat(80000));
        StringBuilder combined = new StringBuilder();
        long offset = 0;
        for (int i = 0; i < 5; i++) {
            var result = CommandOutput.read(root, "stdout", offset, 16000);
            combined.append(result.text());
            offset = result.nextOffset();
        }
        assertThat(combined).hasToString("x".repeat(80000));
        assertThat(offset).isEqualTo(80000);
    }

    @Test
    void utf8PageBoundariesAndAppendedPartialLineRemainReadable() throws Exception {
        Files.writeString(root.resolve("stdout"), "汉汉");
        var first = CommandOutput.read(root, "stdout", 0, 4);
        assertThat(first.text()).isEqualTo("汉");
        assertThat(first.nextOffset()).isEqualTo(3L);
        var second = CommandOutput.read(root, "stdout", 3, 4);
        assertThat(second.text()).isEqualTo("汉");
        assertThat(second.nextOffset()).isEqualTo(6L);
        Files.writeString(root.resolve("stdout"), "字", java.nio.file.StandardOpenOption.APPEND);
        var third = CommandOutput.read(root, "stdout", 6, 4);
        assertThat(third.text()).isEqualTo("字");
        assertThat(third.nextOffset()).isEqualTo(9L);
    }

    @Test
    void diagnosticTailRetainsFinalErrorAndReportsTruncation() throws Exception {
        Files.writeString(root.resolve("stderr"), "x".repeat(30000) + "\nfinal error");
        var result = CommandOutput.tail(root, "stderr");
        assertThat(result.truncated()).isTrue();
        assertThat(result.text()).endsWith("final error");
    }

    @Test
    void limitedStreamBoundsMemoryWhilePreservingFullOutputOnDisk() throws Exception {
        Path file = root.resolve("stdout");
        var stream = new CommandOutput.LimitedOutputStream(8, file);
        try (stream) {
            stream.write("first chunk".getBytes(StandardCharsets.UTF_8));
            assertThat(Files.readString(file)).isEqualTo("first chunk");
            stream.write(" then final diagnostic".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(stream.value()).isEqualTo("first ch");
        assertThat(stream.isTruncated()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("first chunk then final diagnostic");
    }

    @Test
    void missingStreamUsesEmptyWindow() {
        assertThat(CommandOutput.read(root, "stdout", 7, 16))
            .isEqualTo(new CommandStreamWindow("", 7, false));
        assertThat(CommandOutput.tail(root, "stderr")).isEqualTo(new CommandTail("", false));
    }
}

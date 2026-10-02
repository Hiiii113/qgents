package qg.qgent.sandbox.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class DockerCommandOutputTest {
    @TempDir Path root;

    @Test void preservesFullOutputOnDiskWhileBoundingMemory() throws Exception {
        Path file = root.resolve("stdout");
        var stream = new DockerCommandExecutor.LimitedOutputStream(8, file);
        try (stream) {
            stream.write("first chunk".getBytes(StandardCharsets.UTF_8));
            assertThat(Files.readString(file)).isEqualTo("first chunk");
            stream.write(" then final diagnostic".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(stream.value()).isEqualTo("first ch");
        assertThat(stream.isTruncated()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("first chunk then final diagnostic");
    }
}

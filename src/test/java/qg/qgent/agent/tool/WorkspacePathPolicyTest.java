package qg.qgent.agent.tool;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WorkspacePathPolicyTest {
    @Test
    void normalizesRelativePathsAndRejectsOutsidePaths() {
        assertThat(WorkspacePathPolicy.normalize("repo-2\\src/./App.java")).isEqualTo("repo-2/src/App.java");
        assertThat(WorkspacePathPolicy.normalize("../repo-3/file.txt")).isNull();
        assertThat(WorkspacePathPolicy.normalize("C:/repo-3/file.txt")).isNull();
        assertThat(WorkspacePathPolicy.normalize("/tmp/file.txt")).isNull();
    }
}

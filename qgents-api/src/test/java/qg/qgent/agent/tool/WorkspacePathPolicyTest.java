package qg.qgent.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspacePathPolicyTest {

    @Test
    void exactFileIsAllowedButSiblingIsNot() {
        WorkspacePathPolicy policy = WorkspacePathPolicy.of(List.of("repo-2/what the fox said.txt"));

        assertThat(policy.allows("repo-2/what the fox said.txt")).isTrue();
        assertThat(policy.allows("repo-3/holy shit.txt")).isFalse();
    }

    @Test
    void directoryCreationCanReachParentOfDeclaredFile() {
        WorkspacePathPolicy policy = WorkspacePathPolicy.of(List.of("repo-2/src/main/App.java"));

        assertThat(policy.allowsDirectory("repo-2/src/main")).isTrue();
        assertThat(policy.allowsDirectory("repo-3/src/main")).isFalse();
    }

    @Test
    void rejectsAbsoluteAndParentTraversalPaths() {
        WorkspacePathPolicy policy = WorkspacePathPolicy.of(List.of("repo-2/file.txt"));

        assertThat(WorkspacePathPolicy.normalize("../repo-3/file.txt")).isNull();
        assertThat(WorkspacePathPolicy.normalize("C:/repo-3/file.txt")).isNull();
        assertThat(policy.allows("repo-2/../repo-3/file.txt")).isFalse();
    }

    @Test
    void emptyPolicyIsWorkspaceCompatible() {
        WorkspacePathPolicy policy = WorkspacePathPolicy.of(List.of());

        assertThat(policy.isUnrestricted()).isTrue();
        assertThat(policy.allows("workspace/file.txt")).isTrue();
    }
}

package qg.qgent.sandbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandbox.execution.handler.FileEditTool;
import qg.qgent.sandbox.execution.handler.FileGlobTool;
import qg.qgent.sandbox.execution.handler.FilePatchTool;
import qg.qgent.sandbox.execution.handler.FileReadTool;
import qg.qgent.sandbox.execution.handler.FileSearchTool;
import qg.qgent.sandbox.execution.handler.FileWriteTool;
import qg.qgent.sandbox.execution.handler.RepositoryFileResolver;
import qg.qgent.sandbox.filesystem.service.WorkspacePathResolver;
import qg.qgent.sandbox.runtime.CommandExecutor;
import qg.qgent.sandbox.runtime.SandboxAllocation;
import qg.qgent.support.TestIds;

class SandboxFilesWriteTest {
    private final Long id = TestIds.next(), agentRunId = TestIds.next();
    private final UUID sandboxId = UUID.randomUUID();
    private final String storageKey = TestIds.next() + "/workspaces/default";
    private final SandboxSessionManager sessions = mock(SandboxSessionManager.class);
    private final SandboxService sandboxes = mock(SandboxService.class);
    private final WorkspacePathResolver paths = mock(WorkspacePathResolver.class);
    private final CommandExecutor executor = mock(CommandExecutor.class);
    private final RepositoryFileResolver resolver = new RepositoryFileResolver();

    @TempDir Path root;

    private SandboxFiles files() {
        when(sessions.require(id))
            .thenReturn(new SandboxSession(agentRunId, id, sandboxId, storageKey));
        when(paths.resolveLocal(storageKey)).thenReturn(root);
        when(sandboxes.findAllocation(sandboxId)).thenReturn(allocation());
        return new SandboxFiles(
            sessions,
            sandboxes,
            paths,
            new FileGlobTool(resolver),
            new FileReadTool(resolver),
            new FileSearchTool(executor, new ObjectMapper(), resolver),
            new FileWriteTool(resolver),
            new FileEditTool(resolver),
            new FilePatchTool(resolver));
    }

    @Test
    void newFileIsCreatedWithoutReadingCurrentVersion() throws Exception {
        assertThat(files().writeFile(id, ".gitignore", "build"))
            .containsEntry("ok", true)
            .containsEntry("changed", true);
        assertThat(Files.readString(root.resolve(".gitignore"))).isEqualTo("build");
        verify(sessions).renewWriteLease(id);
    }

    @Test
    void replacementUsesObservedVersion() throws Exception {
        Files.writeString(root.resolve("x.txt"), "old");
        String hash = sha256(Files.readAllBytes(root.resolve("x.txt")));

        assertThat(files().replaceFile(id, "x.txt", hash, "new"))
            .containsEntry("ok", true)
            .containsEntry("changed", true);
        assertThat(Files.readString(root.resolve("x.txt"))).isEqualTo("new");
    }

    @Test
    void writeConflictAndEditAmbiguityRemainModelRepairable() throws Exception {
        Files.writeString(root.resolve(".gitignore"), "old");
        assertThat(files().writeFile(id, ".gitignore", "new").get("errorCode"))
            .isEqualTo("FILE_ALREADY_EXISTS");

        Files.writeString(root.resolve("x.txt"), "old old");
        String hash = sha256(Files.readAllBytes(root.resolve("x.txt")));
        assertThat(files().editFile(id, "x.txt", hash, "old", "new", false).get("errorCode"))
            .isEqualTo("EDIT_TEXT_AMBIGUOUS");
    }

    @Test
    void staleHashIsStillRejected() throws Exception {
        Files.writeString(root.resolve("x.txt"), "changed");

        assertThat(files().replaceFile(id, "x.txt", "a".repeat(64), "new"))
            .containsEntry("ok", false)
            .containsEntry("errorCode", "FILE_HASH_MISMATCH");
        assertThat(Files.readString(root.resolve("x.txt"))).isEqualTo("changed");
    }

    @Test
    void oversizedWriteIsRejectedBeforeTouchingTheWorkspace() {
        assertThat(files().writeFile(id, "x", "x".repeat(256 * 1024 + 1)))
            .containsEntry("ok", false)
            .containsEntry("errorCode", "TOOL_ARGUMENT_INVALID");
        verifyNoInteractions(sessions);
    }

    @Test
    void unavailableSandboxRemainsInfrastructureFailure() {
        SandboxFiles files = files();
        when(sessions.require(id)).thenThrow(new IllegalStateException("Sandbox 不可用"));

        assertThat(files.patchFile(id, "x", "a".repeat(64), "patch"))
            .containsEntry("ok", false)
            .containsEntry("errorCode", "TOOL_FAILED");
    }

    @Test
    void lostWriteLeaseIsReportedWithoutWriting() throws Exception {
        Files.writeString(root.resolve("x.txt"), "old");
        SandboxFiles files = files();
        org.mockito.Mockito.doThrow(
                new qg.qgent.common.web.ApiException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "WORKSPACE_WRITE_LEASE_LOST",
                    "Workspace write lease is no longer active"))
            .when(sessions)
            .renewWriteLease(id);

        assertThat(files.editFile(id, "x.txt", "a".repeat(64), "old", "new", false))
            .containsEntry("ok", false)
            .containsEntry("errorCode", "TOOL_FAILED");
        assertThat(Files.readString(root.resolve("x.txt"))).isEqualTo("old");
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private SandboxAllocation allocation() {
        Instant now = Instant.now();
        return new SandboxAllocation(
            sandboxId,
            agentRunId,
            storageKey,
            "dev-tools",
            "READY",
            "FAKE",
            now,
            now,
            now.plus(Duration.ofMinutes(30)),
            now.plus(Duration.ofHours(4)),
            Duration.ofMinutes(15),
            null);
    }
}

package qg.qgent.sandbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import qg.qgent.sandbox.runtime.CommandExecutionResult;
import qg.qgent.sandbox.runtime.CommandExecutor;
import qg.qgent.sandbox.runtime.SandboxAllocation;
import qg.qgent.support.TestIds;

class SandboxFilesReadTest {
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
        when(sessions.require(id)).thenReturn(new SandboxSession(agentRunId, id, sandboxId, storageKey));
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
    void preservesRequestedWindowWithoutFetchingRemainingPages() throws Exception {
        Files.writeString(root.resolve("big.java"), "line\n".repeat(400));
        SandboxFiles files = files();

        var result = files.readFile(id, "big.java", 301, 1);

        assertThat(result)
            .containsEntry("ok", true)
            .containsEntry("nextLine", 302)
            .containsEntry("totalLines", 400)
            .containsEntry("truncated", true);
    }

    @Test
    void discoveryReturnsFilesFromOneInProcessCall() throws Exception {
        Files.writeString(root.resolve(".gitignore"), "build");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.java"), "");

        assertThat((List<String>) files().listFiles(id, ".", "**", 200).get("files"))
            .containsExactly(".gitignore", "src/App.java");
    }

    @Test
    void failedSearchPreservesErrorCodeAndStderr() throws Exception {
        Files.writeString(root.resolve("x.java"), "class X {}");
        when(executor.execute(any(), anyString(), anyList(), any()))
            .thenReturn(new CommandExecutionResult(2, List.of(), List.of("regex parse error")));

        assertThat(files().searchCode(id, "[", ".", "**", 100))
            .containsEntry("ok", false)
            .containsEntry("errorCode", "PROCESS_EXIT_NONZERO")
            .containsEntry("error", "regex parse error");
    }

    @Test
    void unexpectedHandlerFailureIsReportedAsExecutionFailure() throws Exception {
        Files.writeString(root.resolve("x.java"), "class X {}");
        when(executor.execute(any(), anyString(), anyList(), any()))
            .thenReturn(new CommandExecutionResult(0, List.of("not-json"), List.of()));

        assertThat(files().searchCode(id, "class", ".", "**", 100))
            .containsEntry("ok", false)
            .containsEntry("errorCode", "TOOL_EXECUTION_FAILED")
            .containsEntry("error", "工具执行失败");
    }

    @Test
    void missingSessionStillFailsReads() {
        SandboxFiles files = files();
        when(sessions.require(id)).thenThrow(new IllegalStateException("no sandbox session"));

        assertThatThrownBy(() -> files.readFile(id, "x.java", 1, 10))
            .isInstanceOf(IllegalStateException.class);
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

    @Test
    void searchRunsInsideTheSandboxFromWorkspaceRoot() throws Exception {
        Files.writeString(root.resolve("x.java"), "class X {}");
        when(executor.execute(any(), anyString(), anyList(), any()))
            .thenReturn(new CommandExecutionResult(1, List.of(), List.of()));

        assertThat(files().searchCode(id, "missing", ".", "**", 100))
            .containsEntry("matches", List.of());

        verify(executor)
            .execute(
                any(SandboxAllocation.class),
                org.mockito.ArgumentMatchers.eq("/workspace"),
                anyList(),
                any(Duration.class));
    }
}

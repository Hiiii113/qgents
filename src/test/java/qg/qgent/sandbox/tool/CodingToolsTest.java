package qg.qgent.sandbox.tool;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.harness.tool.ToolRegistry;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.project.storage.ProjectDirectoryService;
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
import qg.qgent.sandbox.service.SandboxFiles;
import qg.qgent.sandbox.service.SandboxService;
import qg.qgent.sandbox.service.SandboxSession;
import qg.qgent.sandbox.service.SandboxSessionManager;
import qg.qgent.support.TestIds;
import qg.qgent.workspace.service.WorkSession;

/**
 * 文件工具端到端行为：真实 SandboxFiles 与真实文件处理器读写 {@link TempDir}，
 * 只在沙箱会话与外部命令执行这两个进程边界上打桩。
 */
class CodingToolsTest {
    private final Long workspaceId = TestIds.next();
    private final Long agentRunId = TestIds.next();
    private final UUID sandboxId = UUID.randomUUID();
    private final SandboxSessionManager sessions = mock(SandboxSessionManager.class);
    private final SandboxService sandboxes = mock(SandboxService.class);
    private final CommandExecutor executor = mock(CommandExecutor.class);
    private final RepositoryFileResolver resolver = new RepositoryFileResolver();
    private final ObjectMapper json = new ObjectMapper();

    @TempDir Path root;

    private WorkspacePathResolver paths;
    private SandboxFiles files;
    private SandboxAllocation allocation;
    private Path workspace;

    @BeforeEach
    void wireRealFileToolsToTempWorkspace() {
        paths =
                new WorkspacePathResolver(
                        new ProjectDirectoryService(root.toString(), root.toString()));
        files =
                new SandboxFiles(
                        sessions,
                        sandboxes,
                        paths,
                        new FileGlobTool(resolver),
                        new FileReadTool(resolver),
                        new FileSearchTool(executor, json, resolver),
                        new FileWriteTool(resolver),
                        new FileEditTool(resolver),
                        new FilePatchTool(resolver));
        allocation = allocation(workspaceId);
        when(sandboxes.findAllocation(sandboxId)).thenReturn(allocation);
        workspace = workspace(workspaceId);
    }

    private Path workspace(Long id) {
        String storageKey = id + "/workspaces/default";
        when(sessions.require(id))
                .thenReturn(new SandboxSession(agentRunId, id, sandboxId, storageKey));
        return paths.resolveLocal(storageKey);
    }

    private CodingTools tools() {
        WorkSession session = mock(WorkSession.class);
        when(session.ensure()).thenReturn(workspaceId);
        return new CodingTools(session, files);
    }

    private ToolRegistry registry(Object... tools) {
        ToolRegistry registry = new ToolRegistry(new ObjectMapper());
        registry.register(tools);
        return registry;
    }

    @Test
    void modelSchemaUsesWindowsAndHidesInternalHashesAndOldTools() {
        var callbacks = registry(tools()).callbacks();
        assertThat(callbacks)
                .extracting(callback -> callback.name())
                .containsExactlyInAnyOrder(
                        "read_file",
                        "glob_files",
                        "search_code",
                        "write_file",
                        "edit_file",
                        "apply_patch");
        assertThat(callbacks)
                .allSatisfy(
                        callback ->
                                assertThat(callback.inputSchema())
                                        .doesNotContain("expectedHash"));
        assertThat(
                        callbacks.stream()
                                .filter(callback -> callback.name().equals("read_file"))
                                .findFirst()
                                .orElseThrow()
                                .inputSchema())
                .contains("startLine", "lineCount");
    }

    @Test
    void parallelMetadataMarksReadsAndLeavesWritesExclusive() {
        for (var callback : registry(tools()).callbacks()) {
            boolean read =
                    java.util.Set.of("read_file", "glob_files", "search_code")
                            .contains(callback.name());
            assertThat(callback.parallel()).isEqualTo(read);
        }
    }

    @Test
    void readWindowStopsAtRequestedPageAndReportsRemainingLines() throws Exception {
        Files.writeString(workspace.resolve("big.java"), "line\n".repeat(400));

        ToolResult result = tools().readFile("big.java", 301, 1);

        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("startLine", 301)
                .containsEntry("totalLines", 400)
                .containsEntry("nextLine", 302)
                .containsEntry("truncated", true);
        assertThat((List<Map<String, Object>>) result.get("lines")).hasSize(1);
    }

    @Test
    void readThenWriteEditAndPatchFlowKeepsObservedVersionsInSync() throws Exception {
        CodingTools tools = tools();
        Path file = workspace.resolve("src/notes.txt");

        assertThat(tools.writeFile("src/notes.txt", "one\ntwo\n"))
                .containsEntry("ok", true)
                .containsEntry("changed", true);
        assertThat(Files.readString(file)).isEqualTo("one\ntwo\n");

        assertThat(tools.writeFile("src/notes.txt", "one\ntwo\nthree\n"))
                .containsEntry("changed", true)
                .containsEntry("sha256", sha256(file));

        ToolResult read = tools.readFile("src/notes.txt", null, null);
        assertThat(read)
                .containsEntry("ok", true)
                .containsEntry("totalLines", 3)
                .containsEntry("sha256", sha256(file));

        assertThat(tools.editFile("src/notes.txt", "three", "four", null))
                .containsEntry("changed", true)
                .containsEntry("sha256", sha256(file));
        assertThat(tools.applyPatch("src/notes.txt", "@@ -2,1 +2,1 @@\n-two\n+TWO\n"))
                .containsEntry("changed", true)
                .containsEntry("sha256", sha256(file));
        assertThat(Files.readString(file)).isEqualTo("one\nTWO\nfour\n");
    }

    @Test
    void unobservedExistingFileIsNotOverwrittenWhileHiddenFileCreationWorks() throws Exception {
        Files.writeString(workspace.resolve("keep.txt"), "keep");
        CodingTools tools = tools();

        assertThat(tools.writeFile("keep.txt", "new"))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "FILE_ALREADY_EXISTS");
        assertThat(workspace.resolve("keep.txt")).hasContent("keep");

        assertThat(tools.writeFile("./.gitignore", "build"))
                .containsEntry("ok", true)
                .containsEntry("changed", true);
        assertThat(workspace.resolve(".gitignore")).hasContent("build");
    }

    @Test
    void editAndPatchRequireAReadButWriteCreatesNewFilesDirectly() {
        CodingTools tools = tools();

        assertThat(tools.editFile("x.txt", "old", "new", null))
                .containsEntry("errorCode", "FILE_NOT_READ");
        assertThat(tools.applyPatch("x.txt", "@@ -1,1 +1,1 @@\n-old\n+new\n"))
                .containsEntry("errorCode", "FILE_NOT_READ");
        assertThat(tools.writeFile("x.txt", "content"))
                .containsEntry("ok", true)
                .containsEntry("changed", true);
        assertThat(workspace.resolve("x.txt")).hasContent("content");
    }

    @Test
    void externalChangeAfterReadSurfacesHashConflictForEveryMutation() throws Exception {
        Files.writeString(workspace.resolve("x.txt"), "old");
        CodingTools tools = tools();
        tools.readFile("x.txt", null, null);
        Files.writeString(workspace.resolve("x.txt"), "changed");

        assertThat(tools.writeFile("x.txt", "next"))
                .containsEntry("errorCode", "FILE_HASH_MISMATCH");
        assertThat(tools.editFile("x.txt", "changed", "next", null))
                .containsEntry("errorCode", "FILE_HASH_MISMATCH");
        assertThat(tools.applyPatch("x.txt", "@@ -1,1 +1,1 @@\n-changed\n+next\n"))
                .containsEntry("errorCode", "FILE_HASH_MISMATCH");
        assertThat(workspace.resolve("x.txt")).hasContent("changed");

        tools.readFile("x.txt", null, null);
        assertThat(tools.editFile("x.txt", "changed", "next", null)).containsEntry("ok", true);
        assertThat(workspace.resolve("x.txt")).hasContent("next");
    }

    @Test
    void ambiguousEditStaysRepairableWithoutChangingTheFile() throws Exception {
        Files.writeString(workspace.resolve("dup.txt"), "old old");
        CodingTools tools = tools();
        tools.readFile("dup.txt", null, null);

        assertThat(tools.editFile("dup.txt", "old", "new", null))
                .containsEntry("errorCode", "EDIT_TEXT_AMBIGUOUS");
        assertThat(workspace.resolve("dup.txt")).hasContent("old old");

        assertThat(tools.editFile("dup.txt", "old", "new", true)).containsEntry("ok", true);
        assertThat(workspace.resolve("dup.txt")).hasContent("new new");
    }

    @Test
    void switchingWorkspaceInvalidatesObservedVersions() throws Exception {
        Long otherId = TestIds.next();
        Path other = workspace(otherId);
        Files.writeString(other.resolve("x.txt"), "other");
        Files.writeString(workspace.resolve("x.txt"), "first");

        AtomicReference<Long> selected = new AtomicReference<>(workspaceId);
        WorkSession switching = mock(WorkSession.class);
        when(switching.ensure()).thenAnswer(invocation -> selected.get());
        CodingTools tool = new CodingTools(switching, files);
        tool.readFile("x.txt", null, null);
        selected.set(otherId);

        assertThat(tool.applyPatch("x.txt", "@@ -1,1 +1,1 @@\n-other\n+changed\n"))
                .containsEntry("errorCode", "FILE_NOT_READ");
        assertThat(tool.writeFile("x.txt", "changed"))
                .containsEntry("errorCode", "FILE_ALREADY_EXISTS");
        assertThat(other.resolve("x.txt")).hasContent("other");
    }

    @Test
    void searchRunsThroughTheSandboxAndParsesMatches() throws Exception {
        Files.writeString(workspace.resolve("hello.txt"), "hello world\n");
        when(executor.execute(any(), anyString(), anyList(), any()))
                .thenReturn(
                        new CommandExecutionResult(
                                0, List.of(match("hello.txt", 1, "hello world")), List.of()));

        ToolResult result = tools().searchCode("hello", null, "**/*.txt", null);

        assertThat((List<Map<String, Object>>) result.get("matches"))
                .containsExactly(
                        Map.of(
                                "path",
                                "hello.txt",
                                "lineNumber",
                                1,
                                "text",
                                "hello world",
                                "truncated",
                                false));
        verify(executor)
                .execute(
                        eq(allocation),
                        eq("/workspace"),
                        argThat(
                                command ->
                                        command.contains("hello")
                                                && command.contains("**/*.txt")),
                        eq(Duration.ofSeconds(30)));
    }

    @Test
    void failedSearchKeepsProcessErrorInsteadOfEmptySuccess() throws Exception {
        Files.writeString(workspace.resolve("x.java"), "class X {}");
        when(executor.execute(any(), anyString(), anyList(), any()))
                .thenReturn(
                        new CommandExecutionResult(2, List.of(), List.of("regex parse error")));

        assertThat(tools().searchCode("[", null, null, null))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "PROCESS_EXIT_NONZERO")
                .containsEntry("error", "regex parse error");
    }

    @Test
    void unparsableSearchOutputFailsInsteadOfReturningNoMatches() throws Exception {
        Files.writeString(workspace.resolve("x.java"), "class X {}");
        when(executor.execute(any(), anyString(), anyList(), any()))
                .thenReturn(new CommandExecutionResult(0, List.of("not-json"), List.of()));

        assertThat(tools().searchCode("class", null, null, null))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "TOOL_EXECUTION_FAILED")
                .containsEntry("error", "工具执行失败");
    }

    @Test
    void globDefaultsToListWorkspaceRelativeFilesIncludingHiddenConfig() throws Exception {
        Files.writeString(workspace.resolve(".gitignore"), "build");
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/App.java"), "");

        assertThat((List<String>) tools().globFiles(null, null, null).get("files"))
                .containsExactly(".gitignore", "src/App.java");
    }

    @Test
    void oversizedWriteIsRejectedBeforeTheSandboxIsContacted() {
        assertThat(tools().writeFile("x", "x".repeat(256 * 1024 + 1)))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "TOOL_ARGUMENT_INVALID");
        verify(sessions, never()).require(any());
    }

    @Test
    void unavailableSandboxFailsMutationsButRethrowsForReads() throws Exception {
        Files.writeString(workspace.resolve("x.txt"), "old");
        CodingTools tools = tools();
        tools.readFile("x.txt", null, null);
        when(sessions.require(workspaceId))
                .thenThrow(new IllegalStateException("Sandbox 不可用"));

        assertThat(tools.editFile("x.txt", "old", "new", null))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "TOOL_FAILED")
                .containsEntry("error", "Sandbox 不可用");
        assertThat(tools.applyPatch("x.txt", "@@ -1,1 +1,1 @@\n-old\n+new\n"))
                .containsEntry("errorCode", "TOOL_FAILED");
        assertThat(tools.writeFile("y.txt", "content"))
                .containsEntry("errorCode", "TOOL_FAILED");
        assertThatThrownBy(() -> tools.readFile("x.txt", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sandbox 不可用");
        assertThat(workspace.resolve("x.txt")).hasContent("old");
        assertThat(workspace.resolve("y.txt")).doesNotExist();
    }

    @Test
    void nestedDirectoryResolvesRelativePathsAndNormalizesWindowsSeparators() throws Exception {
        Files.createDirectories(workspace.resolve("src/test"));
        Files.writeString(workspace.resolve("src/README.md"), "# readme\n");
        Files.writeString(workspace.resolve("src/test/App.java"), "class App {}\n");
        WorkSession nested = mock(WorkSession.class);
        when(nested.ensure()).thenReturn(workspaceId);
        when(nested.directory()).thenReturn("src/main");
        CodingTools tool = new CodingTools(nested, files);

        assertThat(tool.readFile("../README.md", null, null))
                .containsEntry("path", "src/README.md");
        assertThat(tool.readFile("..\\test\\App.java", null, null))
                .containsEntry("path", "src/test/App.java");
    }

    @Test
    void pathEscapesAndDirectoryTargetsAreRejectedBeforeTheSandboxIsContacted() {
        WorkSession nested = mock(WorkSession.class);
        when(nested.ensure()).thenReturn(workspaceId);
        when(nested.directory()).thenReturn("src/main");
        CodingTools tool = new CodingTools(nested, files);

        assertThatThrownBy(() -> tool.readFile("../../../outside.txt", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.writeFile("../..", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools().readFile(".", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.globFiles("C:/Windows", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(sessions, never()).require(any());
    }

    @Test
    void invalidArgumentsNeverReachTheSandbox() {
        CodingTools tools = tools();

        assertThatThrownBy(() -> tools.readFile("x", 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.globFiles(null, null, 1001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.searchCode(" ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.writeFile("x", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(sessions, never()).require(any());
        verifyNoInteractions(executor);
    }

    @Test
    void editRejectsBlankOldTextAfterTheFileWasRead() throws Exception {
        Files.writeString(workspace.resolve("x.txt"), "old");
        CodingTools tools = tools();
        tools.readFile("x.txt", null, null);

        assertThatThrownBy(() -> tools.editFile("x.txt", "", "", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(workspace.resolve("x.txt")).hasContent("old");
    }

    private String match(String path, int lineNumber, String text) throws Exception {
        return json.writeValueAsString(
                Map.of(
                        "type",
                        "match",
                        "data",
                        Map.of(
                                "path",
                                Map.of("text", path),
                                "line_number",
                                lineNumber,
                                "lines",
                                Map.of("text", text + "\n"))));
    }

    private SandboxAllocation allocation(Long id) {
        return new SandboxAllocation(
                sandboxId,
                agentRunId,
                id + "/workspaces/default",
                "dev-tools",
                "READY",
                "FAKE",
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH,
                Duration.ofMinutes(15),
                null);
    }

    private static String sha256(Path file) throws Exception {
        return java.util.HexFormat.of()
                .formatHex(
                        java.security.MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(file)));
    }
}

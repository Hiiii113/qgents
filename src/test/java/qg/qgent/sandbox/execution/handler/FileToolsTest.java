package qg.qgent.sandbox.execution.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandbox.exception.SandboxException;
import qg.qgent.sandbox.runtime.CommandExecutionResult;
import qg.qgent.sandbox.runtime.CommandExecutor;

class FileToolsTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    @TempDir Path root;

    private final RepositoryFileResolver resolver = new RepositoryFileResolver();

    @Test
    void readsWindowFromFileLargerThanOld64KbLimit() throws Exception {
        String content = "a".repeat(80) + "\r\n";
        Files.writeString(root.resolve("large.txt"), content.repeat(2000));
        var result =
            new FileReadTool(resolver).read(root, "large.txt", 1500, 2);

        assertThat(result)
            .containsEntry("totalLines", 2000)
            .containsEntry("nextLine", 1502)
            .containsEntry("newlineStyle", "CRLF");
        assertThat((List<Map<String, Object>>) result.get("lines"))
            .hasSize(2)
            .first()
            .satisfies(line -> assertThat(line).containsEntry("number", 1500));
        assertThat(result.get("sha256"))
            .isEqualTo(FileReadTool.sha256(Files.readAllBytes(root.resolve("large.txt"))));
    }

    @Test
    void emptyFileAndNewlineOnlyFileKeepActualLineCount() throws Exception {
        Files.writeString(root.resolve("empty"), "");
        Files.writeString(root.resolve("newline"), "\n");
        var tool = new FileReadTool(resolver);
        assertThat(tool.read(root, "empty", 1, 200))
            .containsEntry("totalLines", 0)
            .containsEntry("lines", List.of());
        assertThat(tool.read(root, "newline", 1, 200))
            .containsEntry("totalLines", 1)
            .containsEntry("endsWithNewline", true);
    }

    @Test
    void capsLargeLineAndSignalsTruncation() throws Exception {
        Files.writeString(root.resolve("big"), "x".repeat(200000));
        var result = new FileReadTool(resolver).read(root, "big", 1, 200);
        assertThat(result).containsEntry("truncated", true);
        assertThat(((Map<?, ?>) ((List<?>) result.get("lines")).getFirst()).get("text").toString())
            .hasSize(4000);
    }

    @Test
    void discoversHiddenFilesAndReportsCap() throws Exception {
        Files.writeString(root.resolve(".gitignore"), "build");
        Files.createDirectories(root.resolve(".github/workflows"));
        Files.writeString(root.resolve(".github/workflows/build.yml"), "on: push");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "internal");
        var tool = new FileGlobTool(resolver);
        assertThat((List<String>) tool.list(root, ".", "**", 200).get("files"))
            .containsExactly(".github/workflows/build.yml", ".gitignore");
        assertThat(tool.list(root, ".", "**", 1)).containsEntry("truncated", true);
    }

    @Test
    void globPatternMatchesRootAndNestedFiles() throws Exception {
        Files.writeString(root.resolve("Root.java"), "");
        Files.createDirectory(root.resolve("src"));
        Files.writeString(root.resolve("src/Nested.java"), "");
        assertThat(
                (List<String>)
                    new FileGlobTool(resolver).list(root, ".", "**/*.java", 200).get("files"))
            .containsExactly("Root.java", "src/Nested.java");
    }

    @Test
    void creationRejectsExistingHiddenFileAndReplacementRejectsMissingEmptyFile() throws Exception {
        Files.writeString(root.resolve(".gitignore"), "old");
        var tool = new FileWriteTool(resolver);
        assertThatThrownBy(() ->
                tool.write(
                    root,
                    ".gitignore",
                    FileReadTool.sha256("old".getBytes()),
                    "new",
                    "create"))
            .isInstanceOf(SandboxException.class)
            .hasMessageContaining("文件已存在");
        assertThat(Files.readString(root.resolve(".gitignore"))).isEqualTo("old");
        assertThatThrownBy(() ->
                tool.write(
                    root,
                    "missing",
                    FileReadTool.sha256(new byte[0]),
                    "new",
                    "replace"))
            .isInstanceOf(SandboxException.class)
            .hasMessageContaining("文件不存在");
        assertThat(root.resolve("missing")).doesNotExist();
    }

    @Test
    void textEditPreservesCrLfAndSupportsDeletion() throws Exception {
        Files.writeString(root.resolve("x"), "one\r\ntwo\r\nthree\r\n");
        var tool = new FileEditTool(resolver);
        tool.edit(
            root,
            "x",
            FileReadTool.sha256(Files.readAllBytes(root.resolve("x"))),
            "one\ntwo",
            "ONE\nTWO",
            false);
        assertThat(Files.readString(root.resolve("x"))).isEqualTo("ONE\r\nTWO\r\nthree\r\n");
        tool.edit(
            root,
            "x",
            FileReadTool.sha256(Files.readAllBytes(root.resolve("x"))),
            "three\n",
            "",
            false);
        assertThat(Files.readString(root.resolve("x"))).isEqualTo("ONE\r\nTWO\r\n");
    }

    @Test
    void ambiguousOrStaleEditDoesNotModifyFile() throws Exception {
        Files.writeString(root.resolve("x"), "old old");
        var tool = new FileEditTool(resolver);
        String hash = FileReadTool.sha256(Files.readAllBytes(root.resolve("x")));
        assertThatThrownBy(() -> tool.edit(root, "x", hash, "old", "new", false))
            .isInstanceOf(SandboxException.class)
            .hasMessageContaining("匹配多处");
        assertThatThrownBy(() -> tool.edit(root, "x", "a".repeat(64), "old", "new", false))
            .isInstanceOf(SandboxException.class)
            .hasMessageContaining("文件已变化");
        assertThat(Files.readString(root.resolve("x"))).isEqualTo("old old");
        tool.edit(root, "x", hash, "old", "new", true);
        assertThat(Files.readString(root.resolve("x"))).isEqualTo("new new");
    }

    @Test
    void searchPreservesColonInFilenameAndMatchingLines() throws Exception {
        var executor = mock(CommandExecutor.class);
        String match =
            new ObjectMapper()
                .writeValueAsString(
                    Map.of(
                        "type",
                        "match",
                        "data",
                        Map.of(
                            "path",
                            Map.of("text", "src/name:part.java"),
                            "line_number",
                            13,
                            "lines",
                            Map.of("text", "hello\n"))));
        when(executor.execute(any(), anyString(), anyList(), any()))
            .thenReturn(new CommandExecutionResult(0, List.of(match), List.of()));
        var result =
            new FileSearchTool(executor, new ObjectMapper(), resolver)
                .search(null, root, "hello", ".", "**", 100, TIMEOUT);
        assertThat((List<Map<String, Object>>) result.get("matches"))
            .containsExactly(
                Map.of(
                    "path",
                    "src/name:part.java",
                    "lineNumber",
                    13,
                    "text",
                    "hello",
                    "truncated",
                    false));
    }

    @Test
    void truncatedOutputKeepsCompleteMatchesAndReportsTruncation() throws Exception {
        var executor = mock(CommandExecutor.class);
        var mapper = new ObjectMapper();
        String complete =
            mapper.writeValueAsString(
                Map.of(
                    "type",
                    "match",
                    "data",
                    Map.of(
                        "path",
                        Map.of("text", "a.txt"),
                        "line_number",
                        1,
                        "lines",
                        Map.of("text", "hello\n"))));
        // 输出按字节截断会把最后一条 JSON 切成半条，截断标记单独成行
        when(executor.execute(any(), anyString(), anyList(), any()))
            .thenReturn(
                new CommandExecutionResult(
                    0,
                    List.of(
                        complete,
                        complete.substring(0, complete.length() / 2),
                        "[输出超过限制，已截断]"),
                    List.of()));
        var result =
            new FileSearchTool(executor, mapper, resolver)
                .search(null, root, "hello", ".", "**", 100, TIMEOUT);
        assertThat((List<Map<String, Object>>) result.get("matches"))
            .containsExactly(
                Map.of(
                    "path",
                    "a.txt",
                    "lineNumber",
                    1,
                    "text",
                    "hello",
                    "truncated",
                    false));
        assertThat(result.get("truncated")).isEqualTo(true);
    }

    @Test
    void noMatchesIsSuccessAndBadRegexIsFailure() throws Exception {
        var executor = mock(CommandExecutor.class);
        var tool = new FileSearchTool(executor, new ObjectMapper(), resolver);
        when(executor.execute(any(), anyString(), anyList(), any()))
            .thenReturn(new CommandExecutionResult(1, List.of(), List.of()));
        assertThat(tool.search(null, root, "missing", ".", "**", 100, TIMEOUT))
            .containsEntry("matches", List.of());
        when(executor.execute(any(), anyString(), anyList(), any()))
            .thenReturn(new CommandExecutionResult(2, List.of(), List.of("regex parse error")));
        assertThat(tool.search(null, root, "[", ".", "**", 100, TIMEOUT))
            .containsEntry("ok", false)
            .containsEntry("errorCode", "PROCESS_EXIT_NONZERO")
            .containsEntry("error", "regex parse error");
    }

    @Test
    void typedArgumentsAreValidatedBeforeTouchingTheFileSystem() {
        assertThatThrownBy(() -> new FileReadTool(resolver).read(root, "x", 1, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lineCount");
        assertThatThrownBy(() -> new FileReadTool(resolver).read(root, " ", 1, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("path");
        assertThatThrownBy(() -> new FileGlobTool(resolver).list(root, ".", "**", 1001))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("limit");
        assertThatThrownBy(() -> new FileEditTool(resolver).edit(root, "x", "a".repeat(64), "", "", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("oldText");
    }
}

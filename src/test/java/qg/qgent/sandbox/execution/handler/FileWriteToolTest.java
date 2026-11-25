package qg.qgent.sandbox.execution.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.sandbox.exception.SandboxException;

class FileWriteToolTest {
    @TempDir Path repository;

    @Test
    void writesOnlyWhenExpectedHashMatches() throws Exception {
        Path file = repository.resolve("example.txt");
        Files.writeString(file, "旧内容", StandardCharsets.UTF_8);
        FileWriteTool tool = new FileWriteTool(new RepositoryFileResolver());
        String expectedHash = FileReadTool.sha256(Files.readAllBytes(file));

        ToolResult result =
            tool.write(repository, "example.txt", expectedHash, "新内容", "replace");

        assertEquals("新内容", Files.readString(file));
        assertEquals(
            FileReadTool.sha256("新内容".getBytes(StandardCharsets.UTF_8)), result.get("sha256"));
        assertEquals(true, result.get("changed"));
    }

    @Test
    void reportsUnchangedWhenContentIsIdentical() throws Exception {
        Path file = repository.resolve("example.txt");
        Files.writeString(file, "原内容", StandardCharsets.UTF_8);
        FileWriteTool tool = new FileWriteTool(new RepositoryFileResolver());
        String expectedHash = FileReadTool.sha256(Files.readAllBytes(file));

        ToolResult result =
            tool.write(repository, "example.txt", expectedHash, "原内容", "replace");

        assertEquals(false, result.get("changed"));
    }

    @Test
    void createsEmptyNewFileReportsChanged() throws Exception {
        FileWriteTool tool = new FileWriteTool(new RepositoryFileResolver());

        ToolResult result =
            tool.write(repository, "empty.txt", FileReadTool.sha256(new byte[0]), "", "create");

        // 新建空文件：previous 与 next 都为空字节数组，但存在性已变化，必须记为真实变更
        assertTrue(Files.exists(repository.resolve("empty.txt")));
        assertEquals(0, Files.size(repository.resolve("empty.txt")));
        assertEquals(true, result.get("changed"));
    }

    @Test
    void createsMissingParentDirectories() throws Exception {
        FileWriteTool tool = new FileWriteTool(new RepositoryFileResolver());

        ToolResult result =
            tool.write(
                repository,
                "src/main/App.java",
                FileReadTool.sha256(new byte[0]),
                "class App {}",
                "create");

        assertEquals("class App {}", Files.readString(repository.resolve("src/main/App.java")));
        assertEquals(true, result.get("changed"));
    }

    @Test
    void rejectsParentDirectorySymlinkEscapeWhenSupported() throws Exception {
        Path outside = Files.createDirectory(repository.resolve("outside"));
        try {
            Files.createSymbolicLink(repository.resolve("linked"), outside);
        } catch (UnsupportedOperationException
                 | java.io.IOException
                 | SecurityException exception) {
            return;
        }

        FileWriteTool tool = new FileWriteTool(new RepositoryFileResolver());
        SandboxException error =
            assertThrows(
                SandboxException.class,
                () ->
                    tool.write(
                        repository,
                        "linked/escape.txt",
                        FileReadTool.sha256(new byte[0]),
                        "must not escape",
                        "replace"));

        assertEquals("TOOL_PATH_INVALID", error.getCode());
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(outside.resolve("escape.txt")));
    }

    @Test
    void rejectsWriteWhenFileChanged() throws Exception {
        Files.writeString(repository.resolve("example.txt"), "已经变化", StandardCharsets.UTF_8);
        FileWriteTool tool = new FileWriteTool(new RepositoryFileResolver());

        assertThrows(
            SandboxException.class,
            () ->
                tool.write(
                    repository,
                    "example.txt",
                    FileReadTool.sha256("旧内容".getBytes(StandardCharsets.UTF_8)),
                    "新内容",
                    "replace"));
    }

    @Test
    void assignsSandboxOwnershipAfterAtomicReplacementWhenRunningAsRoot() throws Exception {
        assumeTrue(
            Files.getFileAttributeView(
                    repository, java.nio.file.attribute.PosixFileAttributeView.class)
                != null);
        assumeTrue(((Number) Files.getAttribute(repository, "unix:uid")).longValue() == 0);

        Path file = repository.resolve("example.txt");
        Files.writeString(file, "old", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(
            file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        FileWriteTool tool = new FileWriteTool(new RepositoryFileResolver());

        tool.write(
            repository,
            "example.txt",
            FileReadTool.sha256(Files.readAllBytes(file)),
            "new",
            "replace");

        assertEquals(10001L, ((Number) Files.getAttribute(file, "unix:uid")).longValue());
        assertEquals(10001L, ((Number) Files.getAttribute(file, "unix:gid")).longValue());
        assertEquals(
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file));
    }
}

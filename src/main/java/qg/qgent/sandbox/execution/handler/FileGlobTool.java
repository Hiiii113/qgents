package qg.qgent.sandbox.execution.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.harness.tool.ToolResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次遍历发现文件；包含隐藏配置，排除版本库元数据、构建产物和工具输出。
 */
@Component
@RequiredArgsConstructor
public class FileGlobTool {
    private final RepositoryFileResolver files;

    /**
     * 列出工作区内匹配 glob 的文件；limit 为返回数量上限。
     */
    public ToolResult list(Path workspace, String path, String glob, int limit) {
        String directory = path == null ? "." : path;
        String pattern = glob == null ? "**" : glob;
        if (directory.isBlank() || directory.length() > 1024)
            throw new IllegalArgumentException("工具参数不合法：path");
        if (pattern.isBlank() || pattern.length() > 512)
            throw new IllegalArgumentException("工具参数不合法：glob");
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("工具参数不合法：limit");
        var target = files.resolveExisting(workspace, directory);
        var matcher = target.getFileSystem().getPathMatcher("glob:" + pattern);
        List<String> matches = new ArrayList<>();
        try {
            Files.walkFileTree(
                target,
                new java.nio.file.SimpleFileVisitor<>() {
                    public java.nio.file.FileVisitResult preVisitDirectory(
                        java.nio.file.Path dir,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                        String name = dir.getFileName().toString();
                        return List.of(
                                ".git",
                                ".svn",
                                ".hg",
                                "node_modules",
                                "target",
                                "build",
                                ".qgents-output")
                            .contains(name)
                            ? java.nio.file.FileVisitResult.SKIP_SUBTREE
                            : java.nio.file.FileVisitResult.CONTINUE;
                    }

                    public java.nio.file.FileVisitResult visitFile(
                        java.nio.file.Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                        if (Thread.currentThread().isInterrupted())
                            throw new IllegalStateException("文件发现已取消");
                        var relative =
                            target.equals(file)
                                ? file.getFileName()
                                : target.relativize(file);
                        if (attrs.isRegularFile()
                            && (matcher.matches(relative)
                            || (pattern.startsWith("**/")
                            && target.getFileSystem()
                            .getPathMatcher(
                                "glob:" + pattern.substring(3))
                            .matches(relative)))) {
                            matches.add(
                                workspace.relativize(file).toString().replace('\\', '/'));
                        }
                        return matches.size() > limit
                            ? java.nio.file.FileVisitResult.TERMINATE
                            : java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
            boolean truncated = matches.size() > limit;
            ToolResult result = ToolResult.ok();
            result.put("files", matches.stream().limit(limit).sorted().toList());
            result.put("truncated", truncated);
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException("文件发现失败：" + exception.getMessage(), exception);
        }
    }
}

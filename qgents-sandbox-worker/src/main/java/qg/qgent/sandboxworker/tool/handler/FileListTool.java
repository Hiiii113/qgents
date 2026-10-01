package qg.qgent.sandboxworker.tool.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import qg.qgent.sandboxworker.tool.dto.ToolArguments;
import qg.qgent.sandboxworker.tool.dto.ToolContext;
import qg.qgent.sandboxworker.tool.dto.ToolResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 列出仓库内指定目录的直接子项。
 */
@Component
@RequiredArgsConstructor
public class FileListTool implements SandboxTool {
    private final RepositoryFileResolver files;

    @Override
    public String name() {
        return "file.list";
    }


    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String relativePath = ToolArguments.optionalString(arguments, "path", ".", 1024);
        Path directory = files.resolveExisting(context.getLocalWorkspace(), relativePath);
        try (var stream = Files.list(directory)) {
            List<Map<String, Object>> items = stream.limit(1000)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> Map.<String, Object>of("name", path.getFileName().toString(),
                            "directory", Files.isDirectory(path)))
                    .toList();
            return ToolResult.value(Map.of("path", relativePath, "items", items));
        } catch (Exception exception) {
            throw new IllegalStateException("列出目录失败", exception);
        }
    }
}

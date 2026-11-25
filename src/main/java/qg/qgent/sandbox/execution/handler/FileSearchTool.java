package qg.qgent.sandbox.execution.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.sandbox.runtime.CommandExecutor;
import qg.qgent.sandbox.runtime.SandboxAllocation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 使用 rg JSON 输出保留路径、行号与正文；无匹配和执行失败分别返回。
 */
@Component
@RequiredArgsConstructor
public class FileSearchTool {
    private final CommandExecutor executor;
    private final ObjectMapper json;
    private final RepositoryFileResolver files;

    /**
     * 在容器内用 ripgrep 搜索工作区；无匹配返回空结果，rg 自身失败返回稳定错误码。
     */
    public ToolResult search(
        SandboxAllocation sandbox,
        Path workspace,
        String query,
        String path,
        String glob,
        int limit,
        Duration timeout)
        throws InterruptedException {
        if (query == null || query.isBlank() || query.length() > 512)
            throw new IllegalArgumentException("工具参数不合法：query");
        String directory = path == null ? "." : path;
        String pattern = glob == null ? "**" : glob;
        if (directory.isBlank() || directory.length() > 1024)
            throw new IllegalArgumentException("工具参数不合法：path");
        if (pattern.isBlank() || pattern.length() > 512)
            throw new IllegalArgumentException("工具参数不合法：glob");
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("工具参数不合法：limit");
        files.resolveExisting(workspace, directory);
        List<String> command =
            new ArrayList<>(
                List.of(
                    "rg",
                    "--json",
                    "--hidden",
                    "--glob",
                    "!.git/**",
                    "--glob",
                    "!.qgents-output/**",
                    "--glob",
                    pattern,
                    "--",
                    query,
                    directory));
        var result = executor.execute(sandbox, "/workspace", command, timeout);
        if (result.getExitCode() > 1)
            return ToolResult.error(
                "PROCESS_EXIT_NONZERO", String.join("\n", result.getStandardError()));
        List<String> lines = new ArrayList<>(result.getStandardOutput());
        boolean truncated = false;
        if (!lines.isEmpty() && lines.get(lines.size() - 1).startsWith("[输出超过限制")) {
            truncated = true;
            lines.remove(lines.size() - 1);
        }
        List<Map<String, Object>> matches = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            JsonNode event;
            try {
                event = json.readTree(lines.get(index));
            } catch (JsonProcessingException exception) {
                // 输出按字节截断时最后一条记录可能不完整：丢弃它，保留前面已解析的匹配
                if (truncated && index == lines.size() - 1) break;
                throw new IllegalStateException("无法解析搜索结果，请缩小搜索范围", exception);
            }
            if (!"match".equals(event.path("type").asText())) continue;
            var data = event.path("data");
            if (matches.size() >= limit) {
                truncated = true;
                continue;
            }
            String text = data.path("lines").path("text").asText().replaceFirst("[\r\n]+$", "");
            boolean clipped = text.length() > 2000;
            matches.add(
                Map.of(
                    "path",
                    data.path("path").path("text").asText(),
                    "lineNumber",
                    data.path("line_number").asInt(),
                    "text",
                    clipped ? text.substring(0, 2000) : text,
                    "truncated",
                    clipped));
        }
        ToolResult value = ToolResult.ok();
        value.put("matches", matches);
        value.put("truncated", truncated);
        return value;
    }
}

package qg.qgent.sandboxworker.tool.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.sandbox.runtime.CommandExecutor;
import qg.qgent.sandboxworker.tool.dto.ToolArguments;
import qg.qgent.sandboxworker.tool.dto.ToolContext;
import qg.qgent.sandboxworker.tool.dto.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 使用 rg JSON 输出保留路径、行号与正文；无匹配和执行失败分别返回。 */
@Component
@RequiredArgsConstructor
public class FileSearchTool implements SandboxTool {
    private final CommandExecutor executor;
    private final ObjectMapper json;
    private final RepositoryFileResolver files;
    public String name() { return "file.search"; }
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) throws InterruptedException {
        String query = ToolArguments.string(arguments, "query", 512);
        String path = ToolArguments.optionalString(arguments, "path", ".", 1024);
        files.resolveExisting(context.getLocalWorkspace(), path);
        String glob = ToolArguments.optionalString(arguments, "glob", "**", 512);
        int limit = ToolArguments.integer(arguments, "limit", 100, 1, 1000);
        List<String> command = new ArrayList<>(List.of("rg", "--json", "--hidden", "--glob", "!.git/**",
                "--glob", "!.qgents-output/**", "--glob", glob, "--", query, path));
        var result = executor.execute(context.getSandbox(), context.getContainerWorkspace(), command, context.getTimeout());
        if (result.getExitCode() > 1) return new ToolResult(result.getExitCode(),
                Map.of("error", String.join("\n", result.getStandardError())), List.of(), List.of());
        List<Map<String, Object>> matches = new ArrayList<>();
        boolean truncated = false;
        try {
            for (String line : result.getStandardOutput()) {
                if (line.startsWith("[输出超过限制")) { truncated = true; continue; }
                var event = json.readTree(line);
                if (!"match".equals(event.path("type").asText())) continue;
                var data = event.path("data");
                if (matches.size() >= limit) { truncated = true; continue; }
                String text = data.path("lines").path("text").asText().replaceFirst("[\r\n]+$", "");
                boolean clipped = text.length() > 2000;
                matches.add(Map.of("path", data.path("path").path("text").asText(),
                        "lineNumber", data.path("line_number").asInt(), "text", clipped ? text.substring(0, 2000) : text,
                        "truncated", clipped));
            }
            return ToolResult.value(Map.of("matches", matches, "truncated", truncated));
        } catch (Exception exception) { throw new IllegalStateException("无法解析搜索结果，请缩小搜索范围", exception); }
    }
}

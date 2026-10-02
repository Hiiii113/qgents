package qg.qgent.sandbox.service;

import org.springframework.stereotype.Component;
import qg.qgent.workspace.service.WorkspaceCodeAccess;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 保留 Sandbox 的读取窗口、搜索命中、截断信息和错误，不重新读取整文件。 */
@Component
public class SandboxCodeAccess implements WorkspaceCodeAccess {
    private final SandboxToolService tools;

    public SandboxCodeAccess(SandboxToolService tools) {
        this.tools = tools;
    }

    public Map<String, Object> listFiles(UUID id, String path, String glob, int limit) {
        return execute(id, "file.glob", Map.of("path", path, "glob", glob, "limit", limit));
    }

    public Map<String, Object> readFile(UUID id, String path, int startLine, int lineCount) {
        return execute(id, "file.read", Map.of("path", path, "startLine", startLine, "lineCount", lineCount));
    }

    public Map<String, Object> searchCode(UUID id, String pattern, String path, String glob, int limit) {
        return execute(id, "file.search", Map.of("query", pattern, "path", path, "glob", glob, "limit", limit));
    }

    private Map<String, Object> execute(UUID id, String tool, Map<String, Object> arguments) {
        var execution = tools.execute(id, tool, arguments, Duration.ofSeconds(30));
        Map<String, Object> result = new LinkedHashMap<>(execution.getResult());
        result.put("ok", "SUCCEEDED".equals(execution.getStatus()));
        if (!"SUCCEEDED".equals(execution.getStatus())) {
            result.put("errorCode", execution.getFailureCode() == null ? "TOOL_FAILED" : execution.getFailureCode());
            result.put("error", execution.getFailureReason() == null ? "文件操作失败" : execution.getFailureReason());
        }
        return result;
    }
}

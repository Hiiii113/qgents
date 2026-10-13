package qg.qgent.sandbox.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Workspace 文件操作入口，统一保留读取窗口、写入版本、执行结果和失败码。 */
@Service
@RequiredArgsConstructor
public class SandboxFiles {
    private static final String EMPTY_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private final SandboxToolService tools;

    public Map<String, Object> listFiles(UUID id, String path, String glob, int limit) {
        return execute(id, "file.glob", Map.of("path", path, "glob", glob, "limit", limit));
    }

    public Map<String, Object> readFile(UUID id, String path, int startLine, int lineCount) {
        return execute(id, "file.read", Map.of("path", path, "startLine", startLine, "lineCount", lineCount));
    }

    public Map<String, Object> searchCode(UUID id, String pattern, String path, String glob, int limit) {
        return execute(id, "file.search", Map.of("query", pattern, "path", path, "glob", glob, "limit", limit));
    }

    public Map<String, Object> writeFile(UUID id, String path, String content) {
        return write(id, path, EMPTY_HASH, content, "create");
    }

    public Map<String, Object> replaceFile(UUID id, String path, String hash, String content) {
        return write(id, path, hash, content, "replace");
    }

    private Map<String, Object> write(UUID id, String path, String hash, String content, String mode) {
        if (content == null || content.getBytes(StandardCharsets.UTF_8).length > 256 * 1024) {
            return error("TOOL_ARGUMENT_INVALID", "内容不能为空引用或超过 256KB");
        }
        return mutate(id, "file.write", Map.of("path", path, "expectedHash", hash, "content", content, "mode", mode));
    }

    public Map<String, Object> patchFile(UUID id, String path, String hash, String patch) {
        if (patch == null || patch.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) {
            return error("TOOL_ARGUMENT_INVALID", "补丁不能为空引用或超过 1MiB");
        }
        return mutate(id, "file.patch", Map.of("path", path, "expectedHash", hash, "patch", patch));
    }

    public Map<String, Object> editFile(UUID id, String path, String hash, String oldText, String newText, boolean replaceAll) {
        return mutate(id, "file.edit", Map.of("path", path, "expectedHash", hash,
                "oldText", oldText, "newText", newText, "replaceAll", replaceAll));
    }

    private Map<String, Object> mutate(UUID id, String tool, Map<String, Object> arguments) {
        try {
            return execute(id, tool, arguments);
        } catch (RuntimeException exception) {
            return error("TOOL_FAILED", exception.getMessage() == null ? "Sandbox 执行失败" : exception.getMessage());
        }
    }

    private Map<String, Object> execute(UUID id, String tool, Map<String, Object> arguments) {
        var execution = tools.execute(id, tool, arguments, Duration.ofSeconds(30));
        Map<String, Object> result = new LinkedHashMap<>(execution.getResult());
        result.put("ok", "SUCCEEDED".equals(execution.getStatus()));
        if (Boolean.FALSE.equals(result.get("ok"))) {
            result.putAll(error(execution.getFailureCode() == null ? "TOOL_FAILED" : execution.getFailureCode(),
                    execution.getFailureReason() == null ? "文件操作失败" : execution.getFailureReason()));
        }
        return result;
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("ok", false, "errorCode", code, "error", message);
    }
}

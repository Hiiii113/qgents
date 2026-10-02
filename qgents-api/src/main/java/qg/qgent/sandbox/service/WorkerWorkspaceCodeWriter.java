package qg.qgent.sandbox.service;

import org.springframework.stereotype.Component;
import qg.qgent.agent.service.AgentWorkerExecutionService;
import qg.qgent.sandbox.client.SandboxWorkerClient;
import qg.qgent.sandbox.config.SandboxWorkerProperties;
import qg.qgent.workspace.dto.WorkspaceWriteResult;
import qg.qgent.workspace.service.WorkspaceCodeWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** 直接透传文件操作；Worker 负责父目录、存在性、版本和原子替换。 */
@Component
public class WorkerWorkspaceCodeWriter extends AbstractWorkerToolPort implements WorkspaceCodeWriter {
    private static final String EMPTY_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    public WorkerWorkspaceCodeWriter(SandboxWorkerClient client, SandboxSessionManager sessions,
            SandboxWorkerProperties properties, AgentWorkerExecutionService executions) {
        super(client, sessions, properties, executions);
    }
    public WorkspaceWriteResult writeFile(UUID id, String path, String content) {
        return write(id, path, EMPTY_HASH, content, "create");
    }
    public WorkspaceWriteResult replaceFile(UUID id, String path, String hash, String content) {
        return write(id, path, hash, content, "replace");
    }
    private WorkspaceWriteResult write(UUID id, String path, String hash, String content, String mode) {
        if (content == null || content.getBytes(StandardCharsets.UTF_8).length > 256 * 1024)
            return WorkspaceWriteResult.fail(path, "TOOL_ARGUMENT_INVALID", "内容不能为空引用或超过 256KB");
        return execute(id, path, "file.write", Map.of("path", path, "expectedHash", hash, "content", content, "mode", mode));
    }
    public WorkspaceWriteResult patchFile(UUID id, String path, String hash, String patch) {
        if (patch == null || patch.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024)
            return WorkspaceWriteResult.fail(path, "TOOL_ARGUMENT_INVALID", "补丁不能为空引用或超过 1MiB");
        return execute(id, path, "file.patch", Map.of("path", path, "expectedHash", hash, "patch", patch));
    }
    public WorkspaceWriteResult editFile(UUID id, String path, String hash, String oldText, String newText, boolean replaceAll) {
        return execute(id, path, "file.edit", Map.of("path", path, "expectedHash", hash,
                "oldText", oldText, "newText", newText, "replaceAll", replaceAll));
    }
    private WorkspaceWriteResult execute(UUID id, String path, String tool, Map<String, Object> arguments) {
        try {
            var execution = executeTool(id, tool, arguments, Duration.ofSeconds(30));
            if (!"SUCCEEDED".equals(execution.getStatus())) return WorkspaceWriteResult.fail(path,
                    execution.getFailureCode() == null ? "TOOL_FAILED" : execution.getFailureCode(),
                    execution.getFailureReason() == null ? "文件修改失败" : execution.getFailureReason());
            var result = resultOf(execution);
            return WorkspaceWriteResult.ok(path, (String) result.get("sha256"), Boolean.TRUE.equals(result.get("changed")));
        } catch (RuntimeException exception) {
            return WorkspaceWriteResult.infraFail(path, exception.getMessage() == null ? "Worker 不可用" : exception.getMessage());
        }
    }
}

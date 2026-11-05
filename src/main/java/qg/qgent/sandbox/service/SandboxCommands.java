package qg.qgent.sandbox.service;

import org.springframework.stereotype.Service;
import qg.qgent.sandbox.execution.dto.ToolExecutionResponse;
import qg.qgent.sandbox.execution.service.ToolExecutionService;
import qg.qgent.workspace.service.WorkspaceCommands;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 短等待返回执行句柄；后续按句柄读取或取消，内部 Git 操作仍等待终态。 */
@Service
public class SandboxCommands implements WorkspaceCommands {
    private final SandboxToolService tools;

    public SandboxCommands(SandboxToolService tools) {
        this.tools = tools;
    }
    public Map<String, Object> run(Long id, String directory, List<String> command, Map<String, String> environment) {
        var execution = tools.start(id, arguments(directory, command), Duration.ofMinutes(10), environment);
        execution = tools.await(execution.getId(), Duration.ofMinutes(15));
        if (!ToolExecutionService.isTerminal(execution.getStatus())) throw new IllegalStateException("命令等待超时");
        return result(execution);
    }
    public Map<String, Object> start(Long id, String directory, List<String> command, int timeoutSeconds, int waitSeconds) {
        var execution = tools.start(id, arguments(directory, command), Duration.ofSeconds(timeoutSeconds), Map.of());
        return result(tools.await(execution.getId(), Duration.ofSeconds(waitSeconds)));
    }
    public Map<String, Object> output(Long id, Long executionId, long stdoutOffset, long stderrOffset, int count, int waitSeconds) {
        tools.requireCommand(id, executionId);
        var execution = tools.await(executionId, Duration.ofSeconds(waitSeconds));
        Map<String, Object> value = result(execution);
        value.putAll(tools.output(executionId, stdoutOffset, stderrOffset, count));
        return value;
    }
    public Map<String, Object> cancel(Long id, Long executionId) {
        var execution = tools.requireCommand(id, executionId);
        return result(ToolExecutionService.isTerminal(execution.getStatus()) ? execution : tools.cancel(executionId));
    }
    private static Map<String, Object> arguments(String directory, List<String> command) {
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("命令不能为空");
        return Map.of("directory", directory == null ? "." : directory, "command", command);
    }
    private static Map<String, Object> result(ToolExecutionResponse execution) {
        Map<String, Object> value = new LinkedHashMap<>(execution.getResult());
        value.put("ok", "SUCCEEDED".equals(execution.getStatus()) || !ToolExecutionService.isTerminal(execution.getStatus()));
        value.put("executionId", execution.getId().toString()); value.put("status", execution.getStatus());
        value.put("running", !ToolExecutionService.isTerminal(execution.getStatus()));
        if (execution.getExitCode() != null) value.put("exitCode", execution.getExitCode());
        if (Boolean.FALSE.equals(value.get("ok"))) {
            value.put("errorCode", execution.getFailureCode() == null ? "COMMAND_FAILED" : execution.getFailureCode());
            value.put("error", execution.getFailureReason() == null ? "命令执行失败" : execution.getFailureReason());
        }
        return value;
    }
}

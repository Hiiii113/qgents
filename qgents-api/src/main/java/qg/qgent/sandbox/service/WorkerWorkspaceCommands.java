package qg.qgent.sandbox.service;

import org.springframework.stereotype.Service;
import qg.qgent.agent.service.AgentWorkerExecutionService;
import qg.qgent.sandbox.client.SandboxWorkerClient;
import qg.qgent.sandbox.config.SandboxWorkerProperties;
import qg.qgent.sandbox.dto.WorkerToolExecution;
import qg.qgent.workspace.service.WorkspaceCommands;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 短等待返回执行句柄；后续按句柄读取或取消，内部 Git 操作仍等待终态。 */
@Service
public class WorkerWorkspaceCommands extends AbstractWorkerToolPort implements WorkspaceCommands {
    public WorkerWorkspaceCommands(SandboxWorkerClient client, SandboxSessionManager sessions,
            SandboxWorkerProperties properties, AgentWorkerExecutionService executions) {
        super(client, sessions, properties, executions);
    }
    public Map<String, Object> run(UUID id, String directory, List<String> command, Map<String, String> environment) {
        return result(executeTool(id, "command.run", arguments(directory, command), Duration.ofMinutes(10), environment));
    }
    public Map<String, Object> start(UUID id, String directory, List<String> command, int timeoutSeconds, int waitSeconds) {
        var execution = submitTool(id, "command.run", arguments(directory, command), Duration.ofSeconds(timeoutSeconds), Map.of());
        return result(awaitTool(id, execution.getId(), Duration.ofSeconds(waitSeconds), false));
    }
    public Map<String, Object> output(UUID id, UUID executionId, long stdoutOffset, long stderrOffset, int count, int waitSeconds) {
        sessions.renewWriteLease(id);
        client.renewSandbox(session(id).sandboxId());
        requireCommand(id, executionId);
        var execution = awaitTool(id, executionId, Duration.ofSeconds(waitSeconds), false);
        Map<String, Object> value = result(execution);
        value.putAll(client.getCommandOutput(executionId, stdoutOffset, stderrOffset, count));
        return value;
    }
    public Map<String, Object> cancel(UUID id, UUID executionId) {
        var execution = requireCommand(id, executionId);
        return result(isTerminal(execution.getStatus()) ? execution : client.cancelToolExecution(executionId));
    }
    private WorkerToolExecution requireCommand(UUID id, UUID executionId) {
        var execution = client.getToolExecution(executionId);
        if (execution == null || !session(id).sandboxId().equals(execution.getSandboxId()) || !"command.run".equals(execution.getTool()))
            throw new IllegalArgumentException("执行句柄不属于当前工作区的命令");
        return execution;
    }
    private static Map<String, Object> arguments(String directory, List<String> command) {
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("命令不能为空");
        return Map.of("directory", directory == null ? "." : directory, "command", command);
    }
    private static Map<String, Object> result(WorkerToolExecution execution) {
        Map<String, Object> value = new LinkedHashMap<>(resultOf(execution));
        value.put("ok", "SUCCEEDED".equals(execution.getStatus()) || !isTerminal(execution.getStatus()));
        value.put("executionId", execution.getId().toString()); value.put("status", execution.getStatus());
        value.put("running", !isTerminal(execution.getStatus()));
        if (execution.getExitCode() != null) value.put("exitCode", execution.getExitCode());
        if (Boolean.FALSE.equals(value.get("ok"))) {
            value.put("errorCode", execution.getFailureCode() == null ? "COMMAND_FAILED" : execution.getFailureCode());
            value.put("error", execution.getFailureReason() == null ? "命令执行失败" : execution.getFailureReason());
        }
        return value;
    }
}

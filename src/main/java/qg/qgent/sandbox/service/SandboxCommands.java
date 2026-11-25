package qg.qgent.sandbox.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.sandbox.dto.SandboxExecutionContext;
import qg.qgent.sandbox.execution.entity.ExecutionStatus;
import qg.qgent.sandbox.execution.output.CommandOutputWindow;
import qg.qgent.sandbox.execution.service.CommandExecution;
import qg.qgent.sandbox.execution.service.CommandExecutionService;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 短等待返回执行句柄；后续按句柄读取或取消，内部 Git 操作仍等待终态。
 */
@Service
@RequiredArgsConstructor
public class SandboxCommands {
    private final SandboxSessionManager sessions;
    private final CommandExecutionService executions;

    /**
     * 内部等待型调用只需要退出码和 stdout，不构造模型可见的 Map。
     */
    public record CommandResult(int exitCode, String stdout) {
    }

    /**
     * 在 Workspace 根目录同步执行一条命令，返回终态退出码与 stdout 尾部。
     */
    public CommandResult git(Long id, List<String> command, Map<String, String> environment) {
        CommandExecution execution = await(session(id), ".", command, environment);
        Object stdout = execution.result().get("stdout");
        String text = stdout instanceof Map<?, ?> value ? String.valueOf(value.get("text")) : "";
        return new CommandResult(
            execution.exitCode() == null ? -1 : execution.exitCode(), text);
    }

    public ToolResult run(
        Long id, String directory, List<String> command, Map<String, String> environment) {
        return result(await(session(id), directory, command, environment));
    }

    public ToolResult start(
        Long id, String directory, List<String> command, int timeoutSeconds, int waitSeconds) {
        requireCommand(command);
        SandboxSession session = session(id);
        CommandExecution execution =
            executions.start(
                session.sandboxId(),
                SandboxExecutionContext.currentAgentRunId(),
                id,
                directory,
                command,
                Duration.ofSeconds(timeoutSeconds),
                Map.of());
        return result(executions.await(execution.id(), Duration.ofSeconds(waitSeconds)));
    }

    public ToolResult output(
        Long id,
        Long executionId,
        long stdoutOffset,
        long stderrOffset,
        int count,
        int waitSeconds) {
        requireCommand(session(id), executionId);
        CommandExecution execution =
            executions.await(executionId, Duration.ofSeconds(waitSeconds));
        ToolResult value = result(execution);
        CommandOutputWindow window =
            executions.output(executionId, stdoutOffset, stderrOffset, count);
        value.put("stdout", window.stdout().toMap());
        value.put("stderr", window.stderr().toMap());
        return value;
    }

    public ToolResult cancel(Long id, Long executionId) {
        CommandExecution execution = requireCommand(session(id), executionId);
        return result(execution.terminal() ? execution : executions.cancel(executionId));
    }

    private CommandExecution await(
        SandboxSession session,
        String directory,
        List<String> command,
        Map<String, String> environment) {
        requireCommand(command);
        CommandExecution execution =
            executions.start(
                session.sandboxId(),
                SandboxExecutionContext.currentAgentRunId(),
                session.workspaceId(),
                directory,
                command,
                Duration.ofMinutes(10),
                environment);
        execution = executions.await(execution.id(), Duration.ofMinutes(15));
        if (!execution.terminal()) throw new IllegalStateException("命令等待超时");
        return execution;
    }

    private SandboxSession session(Long workspaceId) {
        return sessions.require(workspaceId);
    }

    /**
     * 执行句柄必须属于当前工作区的命令，避免跨工作区读取或取消。
     */
    private CommandExecution requireCommand(SandboxSession session, Long executionId) {
        CommandExecution execution = executions.find(executionId);
        if (!session.sandboxId().equals(execution.sandboxId())
            || !"command.run".equals(execution.tool())) {
            throw new IllegalArgumentException("执行句柄不属于当前工作区的命令");
        }
        return execution;
    }

    private static void requireCommand(List<String> command) {
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("命令不能为空");
    }

    private static ToolResult result(CommandExecution execution) {
        boolean terminal = execution.terminal();
        boolean failed = terminal && execution.status() != ExecutionStatus.SUCCEEDED;
        ToolResult value =
            failed
                ? ToolResult.error(
                execution.failureCode() == null
                ? "COMMAND_FAILED"
                : execution.failureCode(),
                execution.failureReason() == null
                ? "命令执行失败"
                : execution.failureReason())
                : ToolResult.ok();
        value.putAll(execution.result());
        value.put("executionId", execution.id().toString());
        value.put("status", execution.status().name());
        value.put("running", !terminal);
        if (execution.exitCode() != null) value.put("exitCode", execution.exitCode());
        return value;
    }
}

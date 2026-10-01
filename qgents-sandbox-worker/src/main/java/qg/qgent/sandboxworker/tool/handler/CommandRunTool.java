package qg.qgent.sandboxworker.tool.handler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.sandbox.runtime.*;
import qg.qgent.sandboxworker.tool.dto.*;
import java.nio.file.*;
import java.util.*;

/** 命令在完整工作区挂载中运行，工作目录由 Agent 选择。 */
@Component
@RequiredArgsConstructor
public class CommandRunTool implements SandboxTool {
    private final CommandExecutor executor;
    public String name() { return "command.run"; }
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) throws InterruptedException {
        Object raw = arguments.get("command");
        if (!(raw instanceof List<?> values) || values.isEmpty() || values.stream().anyMatch(v -> !(v instanceof String)))
            throw new IllegalArgumentException("command 必须是非空参数数组");
        List<String> command = values.stream().map(String.class::cast).toList();
        String directory = Objects.toString(arguments.get("directory"),".");
        String containerDirectory;
        try {
            Path root = context.getLocalWorkspace().toRealPath();
            Path target = root.resolve(directory).normalize().toRealPath();
            if (!target.startsWith(root)) throw new IllegalArgumentException("工作目录超出当前 Task 工作区");
            containerDirectory = "/workspace" + (target.equals(root)?"":"/"+root.relativize(target).toString().replace('\\','/'));
        } catch (java.io.IOException e) { throw new IllegalArgumentException("工作目录不存在", e); }
        CommandExecutionResult result = executor.execute(context.getSandbox(), containerDirectory, command, context.getTimeout(), context.getEnvironment());
        String stdout = String.join("\n", result.getStandardOutput());
        String stderr = String.join("\n", result.getStandardError());
        for (String secret : context.getEnvironment().values()) {
            if (secret != null && secret.length() >= 8) { stdout = stdout.replace(secret,"[redacted]"); stderr = stderr.replace(secret,"[redacted]"); }
        }
        return new ToolResult(result.getExitCode(), Map.of("stdout", stdout,"stderr", stderr), List.of(), List.of());
    }
}

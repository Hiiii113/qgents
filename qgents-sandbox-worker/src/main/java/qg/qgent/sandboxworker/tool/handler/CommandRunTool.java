package qg.qgent.sandboxworker.tool.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.sandbox.runtime.CommandExecutor;
import qg.qgent.sandboxworker.tool.dto.ToolContext;
import qg.qgent.sandboxworker.tool.dto.ToolResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 执行 argv 数组；保存完整输出，结果只携带有界诊断尾部。 */
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
        String directory = java.util.Objects.toString(arguments.get("directory"), ".");
        try {
            Path root = context.getLocalWorkspace().toRealPath();
            Path target = root.resolve(directory).normalize().toRealPath();
            if (!target.startsWith(root) || !java.nio.file.Files.isDirectory(target)) throw new IllegalArgumentException("工作目录必须在工作区内");
            String cwd = "/workspace" + (target.equals(root) ? "" : "/" + root.relativize(target).toString().replace('\\', '/'));
            Path output = CommandOutput.directory(root, context.getExecutionId());
            qg.qgent.sandboxworker.sandbox.runtime.CommandExecutionResult result;
            if (context.getEnvironment().isEmpty()) {
                result = executor.execute(context.getSandbox(), cwd, command, context.getTimeout(), context.getEnvironment(), output);
            } else {
                result = executor.execute(context.getSandbox(), cwd, command, context.getTimeout(), context.getEnvironment());
                java.nio.file.Files.createDirectories(output);
                for (String stream : List.of("stdout", "stderr")) {
                    String rawOutput = String.join("\n", "stdout".equals(stream) ? result.getStandardOutput() : result.getStandardError());
                    var sanitized = CommandOutput.redact(Map.of("text", rawOutput), context.getEnvironment());
                    java.nio.file.Files.writeString(output.resolve(stream), (String) sanitized.get("text"));
                }
            }
            var stdout = CommandOutput.redact(CommandOutput.tail(output, "stdout"), context.getEnvironment());
            var stderr = CommandOutput.redact(CommandOutput.tail(output, "stderr"), context.getEnvironment());
            return new ToolResult(result.getExitCode(), Map.of("stdout", stdout, "stderr", stderr,
                    "outputAvailable", true), List.of(), List.of());
        } catch (java.io.IOException exception) { throw new IllegalArgumentException("工作目录不存在", exception); }
    }
}

package qg.qgent.sandbox.execution.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.sandbox.execution.output.CommandOutput;
import qg.qgent.sandbox.execution.output.CommandTail;
import qg.qgent.sandbox.runtime.CommandExecutionResult;
import qg.qgent.sandbox.runtime.CommandExecutor;
import qg.qgent.sandbox.runtime.SandboxAllocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在沙箱容器内执行参数数组；普通命令保存完整输出，带凭证命令只保存有界脱敏输出。
 */
@Component
@RequiredArgsConstructor
public class CommandRunTool {
    private final CommandExecutor executor;

    /**
     * 命令进程退出码与两个输出流的尾部窗口。
     */
    public record Outcome(int exitCode, CommandTail stdout, CommandTail stderr) {

        /**
         * 持久化与返回给模型的结果形状；不包含临时环境变量。
         */
        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("stdout", stdout.toMap());
            value.put("stderr", stderr.toMap());
            value.put("outputAvailable", true);
            return value;
        }
    }

    /**
     * 执行一条命令。工作目录必须位于 Workspace 内，输出按执行编号保存到工作区旁侧目录。
     */
    public Outcome run(
        SandboxAllocation sandbox,
        Path localWorkspace,
        String directory,
        List<String> command,
        Duration timeout,
        Map<String, String> environment,
        Long executionId)
        throws InterruptedException {
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("命令不能为空");
        String workingDirectory = directory == null || directory.isBlank() ? "." : directory;
        Map<String, String> credentials = environment == null ? Map.of() : environment;
        try {
            Path root = localWorkspace.toRealPath();
            Path target = root.resolve(workingDirectory).normalize().toRealPath();
            if (!target.startsWith(root) || !Files.isDirectory(target))
                throw new IllegalArgumentException("工作目录必须在工作区内");
            String cwd =
                "/workspace"
                    + (target.equals(root)
                    ? ""
                    : "/" + root.relativize(target).toString().replace('\\', '/'));
            Path output = CommandOutput.directory(root, executionId);
            CommandExecutionResult result;
            if (credentials.isEmpty()) {
                result =
                    executor.execute(sandbox, cwd, command, timeout, credentials, output);
            } else {
                result = executor.execute(sandbox, cwd, command, timeout, credentials);
                CommandOutput.save(output, result, credentials);
            }
            return new Outcome(
                result.getExitCode(),
                redact(CommandOutput.tail(output, "stdout"), credentials),
                redact(CommandOutput.tail(output, "stderr"), credentials));
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("工作目录不存在", exception);
        }
    }

    /**
     * 输出落盘前已脱敏；返回模型前再按本次临时凭证兜底。
     */
    private static CommandTail redact(CommandTail tail, Map<String, String> environment) {
        return new CommandTail(CommandOutput.redact(tail.text(), environment), tail.truncated());
    }
}

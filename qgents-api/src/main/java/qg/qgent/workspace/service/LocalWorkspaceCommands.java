package qg.qgent.workspace.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "false", matchIfMissing = true)
public class LocalWorkspaceCommands implements WorkspaceCommands {
    private final WorkspaceService workspaces;

    @Override
    public Map<String, Object> run(UUID workspaceId, String directory, List<String> command,
                                   Map<String, String> environment) {
        var workspace = workspaces.resolve(workspaceId);
        if (!workspace.ready()) throw new IllegalStateException("工作区未就绪");
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("命令不能为空");
        try {
            Path root = workspace.root().toRealPath();
            Path target = root.resolve(directory == null ? "." : directory).normalize().toRealPath();
            if (!target.startsWith(root)) throw new IllegalArgumentException("工作目录超出当前 Task 工作区");
            ProcessBuilder builder = new ProcessBuilder(command).directory(target.toFile());
            builder.environment().putAll(environment);
            Process process = builder.start();
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<String> output = pool.submit(() -> readOutput(process.getInputStream()));
                Future<String> errors = pool.submit(() -> readOutput(process.getErrorStream()));
                try {
                    if (!process.waitFor(600, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("命令执行超时");
                    }
                    String text = output.get();
                    String error = errors.get();
                    for (String value : environment.values()) {
                        if (value != null && value.length() >= 8) {
                            text = text.replace(value, "[redacted]");
                            error = error.replace(value, "[redacted]");
                        }
                    }
                    return Map.of("ok", process.exitValue() == 0, "exitCode", process.exitValue(),
                            "stdout", text, "stderr", error);
                } finally {
                    // 在关闭输出读取线程前结束进程，取消时不会卡在读取管道上。
                    if (process.isAlive()) {
                        process.descendants().forEach(ProcessHandle::destroyForcibly);
                        process.destroyForcibly();
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("命令已取消", exception);
        } catch (IOException | ExecutionException exception) {
            throw new IllegalStateException("命令执行失败", exception);
        }
    }

    private String readOutput(InputStream stream) throws IOException {
        byte[] first = stream.readNBytes(65536);
        stream.transferTo(OutputStream.nullOutputStream());
        return new String(first, StandardCharsets.UTF_8);
    }
}

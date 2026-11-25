package qg.qgent.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 轻量本地代码题库工具：
 * 1. 从 evals/fixtures/ 下的纯源码快照在工作区初始化干净的本地 Git 仓库与固定基线提交；
 * 2. 运行结束后把工作区复制到独立验收目录，注入隐藏测试并执行验收命令，不污染原工作树。
 */
public final class LocalCodingRepoMaterializer {

    private LocalCodingRepoMaterializer() {}

    public static String materializeRepo(Path fixtureDir, Path targetRepoDir)
        throws IOException, InterruptedException {
        if (!Files.isDirectory(fixtureDir)) {
            throw new IllegalArgumentException("代码题库模板目录不存在: " + fixtureDir);
        }
        deleteRecursively(targetRepoDir);
        copyDirectory(fixtureDir, targetRepoDir, false);
        runCommand(targetRepoDir, List.of("git", "init"), 30, true);
        runCommand(targetRepoDir, List.of("git", "config", "core.autocrlf", "false"), 15, true);
        runCommand(targetRepoDir, List.of("git", "config", "user.name", "qgents-eval"), 15, true);
        runCommand(targetRepoDir, List.of("git", "config", "user.email", "eval@qgents.local"), 15, true);
        runCommand(targetRepoDir, List.of("git", "add", "."), 30, true);
        runCommand(targetRepoDir, List.of("git", "commit", "-m", "eval: base commit"), 30, true);
        CommandResult rev = runCommand(targetRepoDir, List.of("git", "rev-parse", "HEAD"), 15, false);
        return rev.output().trim();
    }

    public static CodingVerification verifyInIsolatedCopy(
        Path agentRepoDir, Path hiddenTestDir, String verifyCommand, String baseSha)
        throws IOException, InterruptedException {
        List<String> changedFiles = changedFiles(agentRepoDir, baseSha);
        Path verifyDir = Files.createTempDirectory("qgents-eval-verify-");
        try {
            copyDirectory(agentRepoDir, verifyDir, true);
            if (hiddenTestDir != null && Files.isDirectory(hiddenTestDir)) {
                copyDirectory(hiddenTestDir, verifyDir, false);
            }
            CommandResult result = runShell(verifyDir, verifyCommand, 120);
            return new CodingVerification(
                result.exitCode() == 0, result.exitCode(), result.output(), changedFiles);
        } finally {
            deleteRecursively(verifyDir);
        }
    }

    static List<String> changedFiles(Path repoDir, String baseSha)
        throws IOException, InterruptedException {
        List<String> files = new ArrayList<>();
        if (baseSha != null && !baseSha.isBlank()) {
            CommandResult diff =
                runCommand(repoDir, List.of("git", "diff", "--name-only", baseSha), 15, false);
            for (String line : diff.output().split("\\R")) {
                if (!line.isBlank()) files.add(line.trim().replace('\\', '/'));
            }
        }
        CommandResult untracked =
            runCommand(
                repoDir, List.of("git", "ls-files", "--others", "--exclude-standard"), 15, false);
        for (String line : untracked.output().split("\\R")) {
            String normalized = line.trim().replace('\\', '/');
            if (!normalized.isBlank() && !files.contains(normalized)) {
                files.add(normalized);
            }
        }
        return files;
    }

    private static CommandResult runShell(Path cwd, String command, int timeoutSec)
        throws IOException, InterruptedException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> cmd =
            windows
                ? List.of("cmd", "/c", command)
                : List.of("sh", "-c", command);
        return runCommand(cwd, cmd, timeoutSec, true);
    }

    private static CommandResult runCommand(
        Path cwd, List<String> command, int timeoutSec, boolean mergeStderr)
        throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(mergeStderr);
        Process process = pb.start();
        byte[] bytes = process.getInputStream().readAllBytes();
        boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new CommandResult(-1, "Command timed out after " + timeoutSec + "s");
        }
        return new CommandResult(process.exitValue(), new String(bytes, StandardCharsets.UTF_8));
    }

    private static void copyDirectory(Path source, Path target, boolean skipGit) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path src : walk.toList()) {
                Path rel = source.relativize(src);
                if (skipGit && !rel.toString().isEmpty() && rel.getName(0).toString().equals(".git")) {
                    continue;
                }
                Path dest = target.resolve(rel);
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.compareTo(a))
                .forEach(
                    p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    public record CommandResult(int exitCode, String output) {}

    public record CodingVerification(
        boolean passed, int exitCode, String output, List<String> changedFiles) {}
}

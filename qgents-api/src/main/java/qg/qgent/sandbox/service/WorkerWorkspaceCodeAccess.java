package qg.qgent.sandbox.service;

import org.springframework.stereotype.Component;

import qg.qgent.agent.service.AgentWorkerExecutionService;
import qg.qgent.sandbox.client.SandboxWorkerClient;
import qg.qgent.sandbox.config.SandboxWorkerProperties;
import qg.qgent.sandbox.dto.WorkerToolExecution;
import qg.qgent.workspace.dto.WorkspaceFileReadResult;
import qg.qgent.workspace.service.WorkspaceCodeAccess;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * {@link WorkspaceCodeAccess} 的 Worker 实现：通过 Worker 的 {@code file.list/file.read/file.search}
 * 工具读取代码，替代主后端本地文件系统访问。
 * <p>
 * 形状翻译：Worker 的 file.list 是一级子项、file.read 是分页 lines、file.search 是 ripgrep
 * 原始输出，本类把它们还原为端口契约要求的"递归相对路径列表 / 整段文本 / 命中路径列表"，
 * 并对 Worker 的目录树同样过滤 .git/target 等噪声目录。
 */
@Component
public class WorkerWorkspaceCodeAccess extends AbstractWorkerToolPort implements WorkspaceCodeAccess {

    /**
     * 单文件检索/读取的最大字节数，防止把超大文件塞进 Agent 上下文。
     */
    private static final int MAX_READ_BYTES = 64 * 1024;
    /**
     * 单次 file.read 每页行数。
     */
    private static final int READ_PAGE_LINES = 1000;
    /**
     * 单次 file.list / file.read / file.search 工具超时。
     */
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(30);

    public WorkerWorkspaceCodeAccess(SandboxWorkerClient client, SandboxSessionManager sessions,
                                     SandboxWorkerProperties properties,
                                     AgentWorkerExecutionService workerExecutionService) {
        super(client, sessions, properties, workerExecutionService);
    }

    @Override
    public List<String> listFiles(UUID workspaceId) {
        session(workspaceId);
        List<String> files = listRecursive(workspaceId, ".");
        files.sort(String::compareTo);
        return files;
    }

    @Override
    public WorkspaceFileReadResult readFile(UUID workspaceId, String path) {
        WorkerPathResolver.Target target = WorkerPathResolver.resolve(session(workspaceId), path);
        if (target == null) {
            return WorkspaceFileReadResult.fail(path, "path is empty");
        }
        String sha256 = null;
        Boolean endsWithNewline = null;
        String newlineStyle = null;
        List<String> allLines = new ArrayList<>();
        int startLine = 1;
        while (true) {
            WorkerToolExecution execution = executeTool(workspaceId, "file.read",
                    Map.of("path", target.relativePath(), "startLine", startLine, "lineCount", READ_PAGE_LINES),
                    TOOL_TIMEOUT);
            if (!"SUCCEEDED".equals(execution.getStatus())) {
                return WorkspaceFileReadResult.fail(path,
                        execution.getFailureReason() == null ? "read failed" : execution.getFailureReason());
            }
            Map<String, Object> result = resultOf(execution);
            if (sha256 == null) {
                Object sha = result.get("sha256");
                sha256 = sha == null ? null : String.valueOf(sha);
            }
            if (endsWithNewline == null) {
                Object nl = result.get("endsWithNewline");
                endsWithNewline = nl instanceof Boolean b ? b : null;
            }
            if (newlineStyle == null) {
                Object style = result.get("newlineStyle");
                newlineStyle = style == null ? null : String.valueOf(style);
            }
            Object lines = result.get("lines");
            if (!(lines instanceof List<?> page)) {
                return WorkspaceFileReadResult.fail(path, "read returned no lines");
            }
            for (Object line : page) {
                allLines.add(String.valueOf(line));
            }
            if (!Boolean.TRUE.equals(result.get("truncated"))) {
                break;
            }
            startLine += READ_PAGE_LINES;
            if (allLines.size() > 100_000) {
                return WorkspaceFileReadResult.fail(path, "file too large to read");
            }
        }
        // 按行重组会丢失末尾换行：真实文件以换行结尾时补回，让模型看到与磁盘一致的内容，
        // 避免 replace_file/apply_patch 误删末尾换行。换行风格信息随结果传给模型保持原样改写。
        StringBuilder content = new StringBuilder(String.join("\n", allLines));
        if (Boolean.TRUE.equals(endsWithNewline) && !content.isEmpty()) {
            content.append('\n');
        }
        if (content.toString().getBytes(StandardCharsets.UTF_8).length > MAX_READ_BYTES) {
            return WorkspaceFileReadResult.fail(path, "file exceeds 64KB read limit");
        }
        return WorkspaceFileReadResult.ok(path, content.toString(), sha256, endsWithNewline, newlineStyle);
    }

    @Override
    public List<String> searchCode(UUID workspaceId, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        session(workspaceId);
        TreeSet<String> matches = new TreeSet<>();
        WorkerToolExecution execution = executeTool(workspaceId, "file.search",
                Map.of("query", query, "path", "."), TOOL_TIMEOUT);
        if (!"SUCCEEDED".equals(execution.getStatus())) {
            return List.of();
        }
        Object raw = resultOf(execution).get("matches");
        if (!(raw instanceof List<?> lines)) {
            return List.of();
        }
        for (Object line : lines) {
            String text = String.valueOf(line);
            int colon = text.indexOf(':');
            if (colon > 0) {
                matches.add(text.substring(0, colon));
            }
        }
        return List.copyOf(matches);
    }

    /** 递归列出工作区路径，保留空目录，跳过 .git 与构建目录。 */
    private List<String> listRecursive(UUID workspaceId, String dir) {
        List<String> files = new ArrayList<>();
        WorkerToolExecution execution = executeTool(workspaceId, "file.list",
                Map.of("path", dir), TOOL_TIMEOUT);
        if (!"SUCCEEDED".equals(execution.getStatus())) {
            return files;
        }
        Object items = resultOf(execution).get("items");
        if (!(items instanceof List<?> list)) {
            return files;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            String name = entry.get("name") == null ? null : String.valueOf(entry.get("name"));
            if (name == null || name.startsWith(".")) {
                continue;
            }
            boolean directory = Boolean.TRUE.equals(entry.get("directory"));
            String child = ".".equals(dir) ? name : dir + "/" + name;
            if (directory) {
                if (isIgnoredDirectory(name)) {
                    continue;
                }
                List<String> nested = listRecursive(workspaceId, child);
                if (nested.isEmpty()) {
                    // 空目录：没有可跟踪文件，目录自身作为路径返回，供目标判定/文件清单感知。
                    files.add(child);
                } else {
                    files.addAll(nested);
                }
            } else {
                files.add(child);
            }
        }
        return files;
    }

    private static boolean isIgnoredDirectory(String name) {
        return "target".equals(name) || "node_modules".equals(name) || ".idea".equals(name)
                || "build".equals(name) || ".git".equals(name);
    }
}

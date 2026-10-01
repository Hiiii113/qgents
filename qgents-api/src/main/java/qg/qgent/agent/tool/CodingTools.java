package qg.qgent.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import qg.qgent.workspace.dto.DevelopmentCommandResult;
import qg.qgent.workspace.dto.WorkspaceDirectoryResult;
import qg.qgent.workspace.dto.WorkspaceFileReadResult;
import qg.qgent.workspace.dto.WorkspaceWriteResult;
import qg.qgent.workspace.service.DevelopmentCommandId;
import qg.qgent.workspace.service.DevelopmentCommandPort;
import qg.qgent.workspace.service.WorkspaceCodeAccess;
import qg.qgent.workspace.service.WorkspaceCodeWriter;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工作区文件与开发命令工具。Spring AI 根据注解生成参数 schema，项目 loop 负责执行。
 * 文件操作返回结构化结果；失败交给模型读取后决定重试或停止。
 * 按文件缓存最新 sha256，减少补丁操作前的重复读取。
 */
@Slf4j
public class CodingTools {

    private final java.util.function.Supplier<UUID> workspace;
    private UUID lastWorkspaceId;
    private final WorkspaceCodeAccess codeAccess;
    private final WorkspaceCodeWriter writer;
    /** 仅允许固定 commandId 的开发命令端口；绝不接收或回传 argv、环境变量、cwd、stdout/stderr。 */
    private final DevelopmentCommandPort developmentCommands;
    /**
     * 已确认的文件最新 sha256 快照（read_file / 成功写后更新），用于省略重复读取。
     */
    private final Map<String, String> latestSha256 = new HashMap<>();

    public CodingTools(UUID workspaceId, WorkspaceCodeAccess codeAccess, WorkspaceCodeWriter writer,
                       DevelopmentCommandPort developmentCommands) {
        this(() -> workspaceId, codeAccess, writer, developmentCommands);
    }

    public CodingTools(java.util.function.Supplier<UUID> workspace, WorkspaceCodeAccess codeAccess,
                       WorkspaceCodeWriter writer, DevelopmentCommandPort developmentCommands) {
        this.workspace = workspace;
        this.codeAccess = codeAccess;
        this.writer = writer;
        this.developmentCommands = developmentCommands;
    }

    /**
     * 记录一次写工具调用的脱敏结果并打服务端日志（不携带 patch/文件内容/绝对路径），
     * 工具结果直接返回给模型。
     */
    private void recordOutcome(String toolName, String path, boolean ok, boolean changed,
                               String errorCode, String error) {
        if (ok) {
            log.info("CODING_TOOL_RESULT tool={} path={} ok=true changed={}", toolName, path, changed);
        } else {
            log.warn("CODING_TOOL_RESULT tool={} path={} ok=false errorCode={} error={}",
                    toolName, path, errorCode, error);
        }
    }

    @Tool(name = "list_files", description = "列出工作区所有代码文件的相对路径，无参数")
    public Map<String, Object> listFiles() {
        List<String> files = codeAccess.listFiles(workspaceId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("files", files);
        return result;
    }

    @Tool(name = "read_file", description = "读取文件的文本内容与当前 sha256（超过 64KB 会被拒绝，先 search_code 再按需读取）")
    public Map<String, Object> readFile(@ToolParam(description = "工作区内的相对路径") String path) {
        if (path == null || path.isBlank()) {
            return error("read_file requires non-empty 'path'");
        }
        WorkspaceFileReadResult read = codeAccess.readFile(workspaceId(), path);
        if (read == null || !read.isOk()) {
            return error(read == null || read.getError() == null
                    ? "file not found or unreadable: " + path : read.getError());
        }
        latestSha256.put(path, read.getSha256());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("path", path);
        result.put("content", read.getContent());
        result.put("sha256", read.getSha256());
        // 末尾换行与换行风格：content 可能因按行重组丢失/保留了尾部换行，显式给出元数据，
        // 避免模型改写文件时误删末尾换行或改变换行风格。
        if (read.getEndsWithNewline() != null) {
            result.put("endsWithNewline", read.getEndsWithNewline());
        }
        if (read.getNewlineStyle() != null) {
            result.put("newlineStyle", read.getNewlineStyle());
        }
        return result;
    }

    @Tool(name = "search_code", description = "在代码中检索关键字，返回命中的文件路径列表")
    public Map<String, Object> searchCode(@ToolParam(description = "检索关键字") String query) {
        if (query == null || query.isBlank()) {
            return error("search_code requires non-empty 'query'");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("matches", codeAccess.searchCode(workspaceId(), query));
        return result;
    }

    /**
     * 执行由 Worker 固定模板定义的测试或构建命令。该工具不接收原始命令、参数、环境变量或工作目录，
     * 也不向模型回传 stdout/stderr；详细脱敏日志仅保留在 Worker 的受控运维通道。
     */
    @Tool(name = "run_development_command", description = "执行固定的测试或构建命令。"
            + "commandId 只能是 MAVEN_TEST、MAVEN_PACKAGE、MAVEN_WRAPPER_TEST、GRADLE_TEST、"
            + "GRADLE_WRAPPER_TEST、NPM_TEST；不可传递命令、argv、环境变量或 cwd。"
            + "可选 repositoryPath 必须是工作区中的仓库相对目录，省略为根目录。")
    public Map<String, Object> runDevelopmentCommand(
            @ToolParam(description = "固定开发命令枚举") String commandId,
            @ToolParam(description = "可选：工作区中的仓库相对目录", required = false) String repositoryPath) {
        DevelopmentCommandId id;
        try {
            id = DevelopmentCommandId.valueOf(commandId == null ? "" : commandId);
        } catch (IllegalArgumentException exception) {
            return error("run_development_command requires a supported commandId");
        }
        DevelopmentCommandResult execution = developmentCommands.run(workspaceId(), repositoryPath, id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", execution != null && execution.ok());
        result.put("commandId", id.name());
        if (execution != null && execution.exitCode() != null) {
            result.put("exitCode", execution.exitCode());
        }
        if (execution == null || !execution.ok()) {
            result.put("errorCode", execution == null || execution.failureCode() == null
                    ? "DEVELOPMENT_COMMAND_FAILED" : execution.failureCode());
            result.put("error", execution == null || execution.failureReason() == null
                    ? "固定开发命令执行失败" : execution.failureReason());
        }
        return result;
    }

    @Tool(name = "create_directory", description = "递归创建工作区内目录；目录已存在时幂等成功，不创建 .gitkeep")
    public Map<String, Object> createDirectory(
            @ToolParam(description = "工作区内的相对目录路径") String path) {
        if (path == null || path.isBlank()) {
            return error("create_directory requires non-empty 'path'");
        }
        String denied = ensureWritablePath(path);
        if (denied != null) {
            return error(denied);
        }
        WorkspaceDirectoryResult result = writer.createDirectory(workspaceId(), path);
        if (result.isOk()) {
            recordOutcome("create_directory", result.getPath() == null ? path : result.getPath(),
                    true, result.isCreated(), null, null);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("path", result.getPath());
            ok.put("created", result.isCreated());
            return ok;
        }
        if (result.isInfrastructureFailure()) {
            throw new IllegalStateException(
                    "create_directory infrastructure failure: " + (result.getError() == null
                            ? "workspace unavailable" : result.getError()));
        }
        String message = result.getError() == null ? "directory creation failed" : result.getError();
        recordOutcome("create_directory", result.getPath() == null ? path : result.getPath(), false, false,
                result.getFailureCode(), message);
        return error(result.getFailureCode(), message);
    }

    @Tool(name = "apply_patch", description = "对已有文本文件精确应用统一 Diff（不能用于创建新文件）；"
            + "expectedHash 可省略——省略时使用本会话已确认的最新哈希，否则先 read_file 获取；"
            + "补丁失败时可重新读取文件并修正补丁，或使用 replace_file")
    public Map<String, Object> applyPatch(
            @ToolParam(description = "工作区内的相对路径") String path,
            @ToolParam(description = "可选：期望的当前文件 64 位十六进制 sha256，省略则用已确认的最新哈希", required = false) String expectedHash,
            @ToolParam(description = "统一 Diff 文本") String patch) {
        if (path == null || path.isBlank()) {
            return error("apply_patch requires non-empty 'path'");
        }
        String denied = ensureWritablePath(path);
        if (denied != null) {
            return error(denied);
        }
        workspaceId();
        if (expectedHash == null || expectedHash.isBlank()) {
            expectedHash = latestSha256.get(path);
            if (expectedHash == null) {
                return error("apply_patch: no known hash for '" + path
                        + "'; read_file first to obtain current sha256");
            }
        }
        if (!expectedHash.matches("[0-9a-fA-F]{64}")) {
            return error("apply_patch requires 64-char hex 'expectedHash' from read_file");
        }
        if (patch == null || patch.isBlank()) {
            return error("apply_patch requires non-empty 'patch'");
        }
        WorkspaceWriteResult result = writer.patchFile(workspaceId(), path, expectedHash, patch);
        if (result.isOk()) {
            latestSha256.put(path, result.getNewSha256());
            recordOutcome("apply_patch", path, true, result.isChanged(), null, null);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("path", path);
            ok.put("changed", result.isChanged());
            ok.put("oldSha", expectedHash);
            ok.put("newSha", result.getNewSha256());
            return ok;
        }
        if (result.isInfrastructureFailure()) {
            throw new IllegalStateException(
                    "apply_patch infrastructure failure: " + (result.getError() == null
                            ? "workspace unavailable" : result.getError()));
        }
        String message = result.getError() == null ? "patch failed" : result.getError();
        recordOutcome("apply_patch", path, false, false, result.getFailureCode(), message);
        return error(result.getFailureCode(), message);
    }

    @Tool(name = "replace_file", description = "对已有 UTF-8 文本文件执行带 expectedHash 校验的整文件原子替换；不能创建新文件")
    public Map<String, Object> replaceFile(
            @ToolParam(description = "工作区内的相对路径") String path,
            @ToolParam(description = "read_file 返回的 64 位十六进制 sha256") String expectedHash,
            @ToolParam(description = "完整 UTF-8 文件内容") String content) {
        if (path == null || path.isBlank()) {
            return error("replace_file requires non-empty 'path'");
        }
        String denied = ensureWritablePath(path);
        if (denied != null) {
            return error(denied);
        }
        if (expectedHash == null || !expectedHash.matches("[0-9a-fA-F]{64}")) {
            return error("replace_file requires 64-char hex 'expectedHash' from read_file");
        }
        if (content == null) {
            return error("replace_file requires non-null 'content'");
        }
        WorkspaceWriteResult result = writer.replaceFile(workspaceId(), path, expectedHash, content);
        if (result.isOk()) {
            latestSha256.put(path, result.getNewSha256());
            recordOutcome("replace_file", path, true, result.isChanged(), null, null);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("path", path);
            ok.put("changed", result.isChanged());
            ok.put("oldSha", expectedHash);
            ok.put("newSha", result.getNewSha256());
            return ok;
        }
        if (result.isInfrastructureFailure()) {
            throw new IllegalStateException("replace_file infrastructure failure: "
                    + (result.getError() == null ? "workspace unavailable" : result.getError()));
        }
        String message = result.getError() == null ? "replace failed" : result.getError();
        recordOutcome("replace_file", path, false, false, result.getFailureCode(), message);
        return error(result.getFailureCode(), message);
    }

    @Tool(name = "write_file", description = "创建新文件（目标文件已存在时拒绝，改用 apply_patch 或 replace_file；内容不得超过 256KB）")
    public Map<String, Object> writeFile(
            @ToolParam(description = "工作区内的相对路径") String path,
            @ToolParam(description = "新文件完整内容") String content) {
        if (path == null || path.isBlank()) {
            return error("write_file requires non-empty 'path'");
        }
        String denied = ensureWritablePath(path);
        if (denied != null) {
            return error(denied);
        }
        if (content == null) {
            return error("write_file requires non-empty 'content'");
        }
        if (exists(path)) {
            String message = "write_file only creates new files; '" + path
                    + "' already exists, use apply_patch or replace_file";
            recordOutcome("write_file", path, false, false, "FILE_ALREADY_EXISTS", message);
            return error("FILE_ALREADY_EXISTS", message);
        }
        WorkspaceWriteResult result = writer.writeFile(workspaceId(), path, content);
        if (result.isOk()) {
            latestSha256.put(path, result.getNewSha256());
            recordOutcome("write_file", path, true, result.isChanged(), null, null);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("path", path);
            ok.put("changed", result.isChanged());
            ok.put("oldSha", null);
            ok.put("newSha", result.getNewSha256());
            return ok;
        }
        if (result.isInfrastructureFailure()) {
            throw new IllegalStateException(
                    "write_file infrastructure failure: " + (result.getError() == null
                            ? "workspace unavailable" : result.getError()));
        }
        String message = result.getError() == null ? "write failed" : result.getError();
        recordOutcome("write_file", path, false, false, result.getFailureCode(), message);
        return error(result.getFailureCode(), message);
    }

    @Tool(name = "ensure_trailing_newline", description = "确保已有文本文件以换行结尾：文件当前不以换行结尾时，按 read_file 返回的 expectedHash 校验后追加一个换行（保持原换行风格）；已在文件末尾时幂等返回 changed=false；expectedHash 可省略——省略时使用本会话已确认的最新哈希，否则先 read_file 获取")
    public Map<String, Object> ensureTrailingNewline(
            @ToolParam(description = "工作区内的相对路径") String path,
            @ToolParam(description = "可选：期望的当前文件 64 位十六进制 sha256，省略则用已确认的最新哈希", required = false) String expectedHash) {
        if (path == null || path.isBlank()) {
            return error("ensure_trailing_newline requires non-empty 'path'");
        }
        String denied = ensureWritablePath(path);
        if (denied != null) {
            return error(denied);
        }
        workspaceId();
        if (expectedHash == null || expectedHash.isBlank()) {
            expectedHash = latestSha256.get(path);
            if (expectedHash == null) {
                return error("ensure_trailing_newline: no known hash for '" + path
                        + "'; read_file first to obtain current sha256");
            }
        }
        if (!expectedHash.matches("[0-9a-fA-F]{64}")) {
            return error("ensure_trailing_newline requires 64-char hex 'expectedHash' from read_file");
        }
        WorkspaceWriteResult result = writer.ensureTrailingNewline(workspaceId(), path, expectedHash);
        if (result.isOk()) {
            latestSha256.put(path, result.getNewSha256());
            recordOutcome("ensure_trailing_newline", path, true, result.isChanged(), null, null);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("path", path);
            ok.put("changed", result.isChanged());
            ok.put("newSha", result.getNewSha256());
            return ok;
        }
        if (result.isInfrastructureFailure()) {
            throw new IllegalStateException("ensure_trailing_newline infrastructure failure: "
                    + (result.getError() == null ? "workspace unavailable" : result.getError()));
        }
        String message = result.getError() == null ? "ensure trailing newline failed" : result.getError();
        recordOutcome("ensure_trailing_newline", path, false, false,
                result.getFailureCode(), message);
        return error(result.getFailureCode(), message);
    }

    private UUID workspaceId() {
        UUID selected = workspace.get();
        if (!selected.equals(lastWorkspaceId)) {
            latestSha256.clear();
            lastWorkspaceId = selected;
        }
        return selected;
    }

    /** 已存在文件不能用 write_file 覆盖。 */
    private boolean exists(String path) {
        String normalized = path.replace('\\', '/');
        return codeAccess.listFiles(workspaceId()).stream()
                .anyMatch(file -> normalized.equals(file.replace('\\', '/')));
    }

    private String ensureWritablePath(String path) {
        if (WorkspacePathPolicy.normalize(path) == null) {
            return "path is invalid or escapes the workspace";
        }
        return null;
    }

    private Map<String, Object> error(String message) {
        return error("TOOL_ARGUMENT_INVALID", message);
    }

    private Map<String, Object> error(String failureCode, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("errorCode", failureCode == null ? "TOOL_FAILED" : failureCode);
        result.put("error", message);
        return result;
    }

}

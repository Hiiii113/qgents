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
import qg.qgent.workspace.service.WorkspaceInfraException;

import java.util.Collection;
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

    /**
     * apply_patch 对同一文件连续失败的阈值；达到后强制切换为带 Hash 校验的整文件替换。
     */
    static final int PATCH_FAILURE_ESCALATION_THRESHOLD = 3;

    private final UUID workspaceId;
    private final WorkspaceCodeAccess codeAccess;
    private final WorkspaceCodeWriter writer;
    /** 仅允许固定 commandId 的开发命令端口；绝不接收或回传 argv、环境变量、cwd、stdout/stderr。 */
    private final DevelopmentCommandPort developmentCommands;
    /**
     * 已确认的文件最新 sha256 快照（read_file / 成功写后更新），用于省略重复读取。
     */
    private final Map<String, String> latestSha256 = new HashMap<>();
    /**
     * apply_patch 对同一文件连续失败的次数。达到 {@link #PATCH_FAILURE_ESCALATION_THRESHOLD}
     * 后要求模型使用 replace_file，避免严格 patcher 在模型反复重放时进入死循环。
     */
    private final Map<String, Integer> patchFailuresByPath = new HashMap<>();
    private final WorkspacePathPolicy pathPolicy;

    public CodingTools(UUID workspaceId, WorkspaceCodeAccess codeAccess, WorkspaceCodeWriter writer) {
        this(workspaceId, codeAccess, writer, List.of());
    }

    public CodingTools(UUID workspaceId, WorkspaceCodeAccess codeAccess, WorkspaceCodeWriter writer,
                       Collection<String> allowedPaths) {
        this(workspaceId, codeAccess, writer, allowedPaths, DevelopmentCommandPort.unavailable());
    }

    public CodingTools(UUID workspaceId, WorkspaceCodeAccess codeAccess, WorkspaceCodeWriter writer,
                       Collection<String> allowedPaths, DevelopmentCommandPort developmentCommands) {
        this.workspaceId = workspaceId;
        this.codeAccess = codeAccess;
        this.writer = writer;
        this.developmentCommands = developmentCommands;
        this.pathPolicy = WorkspacePathPolicy.of(allowedPaths);
    }

    /**
     * 记录一次写工具调用的脱敏结果并打服务端日志（不携带 patch/文件内容/绝对路径），
     * 工具结果直接返回给模型。
     */
    private void recordOutcome(String toolName, String path, boolean ok, boolean changed,
                               String errorCode, boolean retryable, String error) {
        if (ok) {
            log.info("CODING_TOOL_RESULT tool={} path={} ok=true changed={}", toolName, path, changed);
        } else {
            log.warn("CODING_TOOL_RESULT tool={} path={} ok=false errorCode={} retryable={} error={}",
                    toolName, path, errorCode, retryable, error);
        }
    }

    @Tool(name = "list_files", description = "列出工作区所有代码文件的相对路径，无参数")
    public Map<String, Object> listFiles() {
        List<String> files = codeAccess.listFiles(workspaceId);
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
        WorkspaceFileReadResult read = codeAccess.readFile(workspaceId, path);
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
        result.put("matches", codeAccess.searchCode(workspaceId, query));
        return result;
    }

    /**
     * 执行由 Worker 固定模板定义的测试或构建命令。该工具不接收原始命令、参数、环境变量或工作目录，
     * 也不向模型回传 stdout/stderr；详细脱敏日志仅保留在 Worker 的受控运维通道。
     */
    @Tool(name = "run_development_command", description = "执行固定的测试或构建命令。"
            + "commandId 只能是 MAVEN_TEST、MAVEN_PACKAGE、MAVEN_WRAPPER_TEST、GRADLE_TEST、"
            + "GRADLE_WRAPPER_TEST、NPM_TEST；不可传递命令、argv、环境变量或 cwd。"
            + "可选 repositoryPath 必须是当前工作区已绑定仓库的一级相对路径，多仓库时必填。")
    public Map<String, Object> runDevelopmentCommand(
            @ToolParam(description = "固定开发命令枚举") String commandId,
            @ToolParam(description = "可选：当前工作区已绑定仓库的一级相对路径", required = false) String repositoryPath) {
        DevelopmentCommandId id;
        try {
            id = DevelopmentCommandId.valueOf(commandId == null ? "" : commandId);
        } catch (IllegalArgumentException exception) {
            return error("run_development_command requires a supported commandId");
        }
        DevelopmentCommandResult execution = developmentCommands.run(workspaceId, repositoryPath, id);
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
        String denied = ensureDirectoryPath(path);
        if (denied != null) {
            return error(denied);
        }
        WorkspaceDirectoryResult result = writer.createDirectory(workspaceId, path);
        if (result.isOk()) {
            recordOutcome("create_directory", result.getPath() == null ? path : result.getPath(),
                    true, result.isChanged(), null, false, null);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("path", result.getPath());
            ok.put("created", result.isCreated());
            return ok;
        }
        if (result.isInfrastructureFailure()) {
            throw new WorkspaceInfraException(
                    "create_directory infrastructure failure: " + (result.getError() == null
                            ? "workspace unavailable" : result.getError()));
        }
        String message = result.getError() == null ? "directory creation failed" : result.getError();
        recordOutcome("create_directory", result.getPath() == null ? path : result.getPath(), false, false,
                classifyError(result.getFailureCode(), message), isRetryable(result.getFailureCode(), message), message);
        return error(result.getFailureCode(), message);
    }

    @Tool(name = "apply_patch", description = "对已有文本文件精确应用统一 Diff（不能用于创建新文件）；"
            + "expectedHash 可省略——省略时使用本会话已确认的最新哈希，否则先 read_file 获取；"
            + "同一文件连续失败 " + PATCH_FAILURE_ESCALATION_THRESHOLD + " 次后必须改用 replace_file")
    public Map<String, Object> applyPatch(
            @ToolParam(description = "工作区内的相对路径") String path,
            @ToolParam(description = "可选：期望的当前文件 64 位十六进制 sha256，省略则用已确认的最新哈希") String expectedHash,
            @ToolParam(description = "统一 Diff 文本") String patch) {
        if (path == null || path.isBlank()) {
            return error("apply_patch requires non-empty 'path'");
        }
        String denied = ensureWritablePath(path);
        if (denied != null) {
            return error(denied);
        }
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
        WorkspaceWriteResult result = writer.patchFile(workspaceId, path, expectedHash, patch);
        if (result.isOk()) {
            // 成功应用后重置该文件的连续失败计数，避免历史失败把后续成功误判为升级。
            patchFailuresByPath.remove(path);
            latestSha256.put(path, result.getNewSha256());
            recordOutcome("apply_patch", path, true, result.isChanged(), null, false, null);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("path", path);
            ok.put("changed", result.isChanged());
            ok.put("oldSha", expectedHash);
            ok.put("newSha", result.getNewSha256());
            return ok;
        }
        if (result.isInfrastructureFailure()) {
            throw new WorkspaceInfraException(
                    "apply_patch infrastructure failure: " + (result.getError() == null
                            ? "workspace unavailable" : result.getError()));
        }
        return patchFailed(path, result.getFailureCode(), result.getError() == null ? "patch failed" : result.getError());
    }

    /**
     * apply_patch 工具级失败：按文件累计连续失败次数，达到阈值后要求使用
     * replace_file，并返回带专门 nextAction 的错误；每次失败都记录脱敏结果。
     */
    private Map<String, Object> patchFailed(String path, String failureCode, String error) {
        int failures = patchFailuresByPath.merge(path, 1, Integer::sum);
        String message;
        if (failures >= PATCH_FAILURE_ESCALATION_THRESHOLD) {
            message = "该文件已连续 " + PATCH_FAILURE_ESCALATION_THRESHOLD + " 次补丁失败（" + error
                    + "），请停止生成 Patch，先 read_file 获取最新内容和 sha256，再调用 replace_file 提供完整文件内容";
            recordOutcome("apply_patch", path, false, false, "TOOL_PATCH_REPAIR_REQUIRED", true, message);
            return error("TOOL_PATCH_REPAIR_REQUIRED", message);
        } else {
            message = error;
        }
        recordOutcome("apply_patch", path, false, false, classifyError(failureCode, message),
                isRetryable(failureCode, message), message);
        return error(failureCode, message);
    }

    @Tool(name = "replace_file", description = "对已有 UTF-8 文本文件执行带 expectedHash 校验的整文件原子替换；仅在 apply_patch 连续失败后使用，不能创建新文件")
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
        WorkspaceWriteResult result = writer.replaceFile(workspaceId, path, expectedHash, content);
        if (result.isOk()) {
            patchFailuresByPath.remove(path);
            latestSha256.put(path, result.getNewSha256());
            recordOutcome("replace_file", path, true, result.isChanged(), null, false, null);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("path", path);
            ok.put("changed", result.isChanged());
            ok.put("oldSha", expectedHash);
            ok.put("newSha", result.getNewSha256());
            return ok;
        }
        if (result.isInfrastructureFailure()) {
            throw new WorkspaceInfraException("replace_file infrastructure failure: "
                    + (result.getError() == null ? "workspace unavailable" : result.getError()));
        }
        String message = result.getError() == null ? "replace failed" : result.getError();
        recordOutcome("replace_file", path, false, false, classifyError(result.getFailureCode(), message),
                isRetryable(result.getFailureCode(), message), message);
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
            recordOutcome("write_file", path, false, false, classifyError(null, message),
                    isRetryable(null, message), message);
            return error(message);
        }
        WorkspaceWriteResult result = writer.writeFile(workspaceId, path, content);
        if (result.isOk()) {
            latestSha256.put(path, result.getNewSha256());
            recordOutcome("write_file", path, true, result.isChanged(), null, false, null);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("path", path);
            ok.put("changed", result.isChanged());
            ok.put("oldSha", null);
            ok.put("newSha", result.getNewSha256());
            return ok;
        }
        if (result.isInfrastructureFailure()) {
            throw new WorkspaceInfraException(
                    "write_file infrastructure failure: " + (result.getError() == null
                            ? "workspace unavailable" : result.getError()));
        }
        String message = result.getError() == null ? "write failed" : result.getError();
        recordOutcome("write_file", path, false, false, classifyError(result.getFailureCode(), message),
                isRetryable(result.getFailureCode(), message), message);
        return error(result.getFailureCode(), message);
    }

    @Tool(name = "ensure_trailing_newline", description = "确保已有文本文件以换行结尾：文件当前不以换行结尾时，按 read_file 返回的 expectedHash 校验后追加一个换行（保持原换行风格）；已在文件末尾时幂等返回 changed=false；expectedHash 可省略——省略时使用本会话已确认的最新哈希，否则先 read_file 获取")
    public Map<String, Object> ensureTrailingNewline(
            @ToolParam(description = "工作区内的相对路径") String path,
            @ToolParam(description = "可选：期望的当前文件 64 位十六进制 sha256，省略则用已确认的最新哈希") String expectedHash) {
        if (path == null || path.isBlank()) {
            return error("ensure_trailing_newline requires non-empty 'path'");
        }
        String denied = ensureWritablePath(path);
        if (denied != null) {
            return error(denied);
        }
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
        WorkspaceWriteResult result = writer.ensureTrailingNewline(workspaceId, path, expectedHash);
        if (result.isOk()) {
            patchFailuresByPath.remove(path);
            latestSha256.put(path, result.getNewSha256());
            recordOutcome("ensure_trailing_newline", path, true, result.isChanged(), null, false, null);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("path", path);
            ok.put("changed", result.isChanged());
            ok.put("newSha", result.getNewSha256());
            return ok;
        }
        if (result.isInfrastructureFailure()) {
            throw new WorkspaceInfraException("ensure_trailing_newline infrastructure failure: "
                    + (result.getError() == null ? "workspace unavailable" : result.getError()));
        }
        String message = result.getError() == null ? "ensure trailing newline failed" : result.getError();
        recordOutcome("ensure_trailing_newline", path, false, false,
                classifyError(result.getFailureCode(), message),
                isRetryable(result.getFailureCode(), message), message);
        return error(result.getFailureCode(), message);
    }

    /**
     * 文件树包含性探测：已存在文件（含子目录）在 list_files 结果中，禁止 write_file 覆盖。
     */
    private boolean exists(String path) {
        String normalized = path.replace('\\', '/');
        return codeAccess.listFiles(workspaceId).stream()
                .anyMatch(file -> normalized.equals(file.replace('\\', '/')));
    }

    private String ensureWritablePath(String path) {
        if (WorkspacePathPolicy.normalize(path) == null) {
            return "path is invalid or escapes the workspace";
        }
        if (!pathPolicy.allows(path)) {
            return "path is outside the configured workspace paths";
        }
        return null;
    }

    private String ensureDirectoryPath(String path) {
        if (WorkspacePathPolicy.normalize(path) == null) {
            return "path is invalid or escapes the workspace";
        }
        if (!pathPolicy.allowsDirectory(path)) {
            return "path is outside the configured workspace paths";
        }
        return null;
    }

    private Map<String, Object> error(String message) {
        return error(null, message);
    }

    private Map<String, Object> error(String failureCode, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("errorCode", classifyError(failureCode, message));
        result.put("retryable", isRetryable(failureCode, message));
        result.put("error", message);
        result.put("nextAction", nextAction(failureCode, message));
        return result;
    }

    private String classifyError(String message) {
        return classifyError(null, message);
    }

    private String classifyError(String failureCode, String message) {
        if (failureCode != null) {
            return switch (failureCode) {
                case "FILE_PATCH_FAILED" -> "TOOL_PATCH_FORMAT_INVALID";
                case "TOOL_PATCH_REPAIR_REQUIRED" -> "TOOL_PATCH_REPAIR_REQUIRED";
                case "FILE_HASH_MISMATCH" -> "TOOL_CONFLICT";
                case "TOOL_PATH_INVALID" -> "TOOL_PATH_INVALID";
                case "TOOL_ARGUMENT_INVALID", "COMMAND_NOT_ALLOWED" -> "TOOL_ARGUMENT_INVALID";
                default -> "TOOL_EXECUTION_FAILED";
            };
        }
        if (message == null) {
            return "TOOL_EXECUTION_FAILED";
        }
        if (message.contains("UTF-8") || message.contains("exceeds")) {
            return "TOOL_CONTENT_INVALID";
        }
        if (message.contains("FILE_PATCH_FAILED") || message.contains("PATCH_")
                || message.contains("补丁") || message.contains("hunk")) {
            return "TOOL_PATCH_FORMAT_INVALID";
        }
        if (message.contains("outside") || message.contains("escapes") || message.contains("invalid")) {
            return "TOOL_PATH_INVALID";
        }
        if (message.contains("FILE_HASH_MISMATCH") || message.contains("hash") || message.contains("changed since read")) {
            return "TOOL_CONFLICT";
        }
        if (message.contains("requires") || message.contains("non-empty")) {
            return "TOOL_ARGUMENT_INVALID";
        }
        return "TOOL_EXECUTION_FAILED";
    }

    private boolean isRetryable(String message) {
        return isRetryable(null, message);
    }

    private boolean isRetryable(String failureCode, String message) {
        if (failureCode != null) {
            return "FILE_PATCH_FAILED".equals(failureCode) || "FILE_HASH_MISMATCH".equals(failureCode)
                    || "TOOL_PATCH_REPAIR_REQUIRED".equals(failureCode);
        }
        if (message != null && (message.contains("FILE_PATCH_FAILED") || message.contains("PATCH_")
                || message.contains("补丁") || message.contains("hunk"))) {
            return true;
        }
        return message != null && !message.contains("outside") && !message.contains("escapes")
                && !message.contains("invalid") && !message.contains("already exists")
                && !message.contains("exceeds") && !message.contains("UTF-8");
    }

    private String nextAction(String message) {
        return nextAction(null, message);
    }

    private String nextAction(String failureCode, String message) {
        if ("TOOL_PATCH_REPAIR_REQUIRED".equals(failureCode)
                || (message != null && message.contains("请停止生成 Patch"))) {
            return "该文件已连续 " + PATCH_FAILURE_ESCALATION_THRESHOLD
                    + " 次补丁失败；请先用 read_file 获取最新内容，再调用 replace_file 提供完整文件内容";
        }
        if ("FILE_PATCH_FAILED".equals(failureCode)) {
            return "不要重复原 patch；先 read_file 获取最新内容和 sha256，再按实际内容重新生成完整 unified diff；新文件改用 write_file";
        }
        if ("FILE_HASH_MISMATCH".equals(failureCode)) {
            return "先重新 read_file 获取当前 sha256，再用 apply_patch";
        }
        if (message == null) {
            return "检查工具参数和工作区状态后再试一次";
        }
        if (message.contains("FILE_PATCH_FAILED") || message.contains("PATCH_")
                || message.contains("补丁") || message.contains("hunk")) {
            return "不要重复原 patch；先 read_file 获取最新内容和 sha256，再按实际内容重新生成完整 unified diff；新文件改用 write_file";
        }
        if (message.contains("FILE_HASH_MISMATCH") || message.contains("hash") || message.contains("changed since read")) {
            return "先重新 read_file 获取当前 sha256，再用 apply_patch";
        }
        if (message.contains("only creates new files") || message.contains("already exists")) {
            return "已有文件必须使用 apply_patch，不要用 write_file 覆盖";
        }
        if (message.contains("exceeds") || message.contains("UTF-8")) {
            return "缩小内容或使用 UTF-8 文本后再调用，不要原样重试";
        }
        if (message.contains("outside") || message.contains("escapes") || message.contains("invalid")) {
            return "改用当前工作区内的相对路径，不要重试越界路径";
        }
        return "根据 error 修正参数后重试一次，不要原样重复失败调用";
    }
}

package qg.qgent.sandbox.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.sandbox.exception.SandboxException;
import qg.qgent.sandbox.execution.handler.FileEditTool;
import qg.qgent.sandbox.execution.handler.FileGlobTool;
import qg.qgent.sandbox.execution.handler.FilePatchTool;
import qg.qgent.sandbox.execution.handler.FileReadTool;
import qg.qgent.sandbox.execution.handler.FileSearchTool;
import qg.qgent.sandbox.execution.handler.FileWriteTool;
import qg.qgent.sandbox.filesystem.service.WorkspacePathResolver;
import qg.qgent.sandbox.runtime.SandboxAllocation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Workspace 文件操作入口：校验并续期工作区会话后直接调用进程内文件处理器，
 * 统一保留读取窗口、写入版本、执行结果和失败码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxFiles {
    private static final String EMPTY_HASH =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(30);
    private final SandboxSessionManager sessions;
    private final SandboxService sandboxes;
    private final WorkspacePathResolver paths;
    private final FileGlobTool glob;
    private final FileReadTool read;
    private final FileSearchTool search;
    private final FileWriteTool write;
    private final FileEditTool edit;
    private final FilePatchTool patch;

    public ToolResult listFiles(Long id, String path, String glob, int limit) {
        return execute(id, (session, workspace) -> this.glob.list(workspace, path, glob, limit));
    }

    public ToolResult readFile(Long id, String path, int startLine, int lineCount) {
        return execute(id, (session, workspace) ->
            read.read(workspace, path, startLine, lineCount));
    }

    public ToolResult searchCode(Long id, String pattern, String path, String glob, int limit) {
        return execute(id, (session, workspace) -> {
            SandboxAllocation sandbox = sandboxes.findAllocation(session.sandboxId());
            return search.search(sandbox, workspace, pattern, path, glob, limit, SEARCH_TIMEOUT);
        });
    }

    public ToolResult writeFile(Long id, String path, String content) {
        return write(id, path, EMPTY_HASH, content, "create");
    }

    public ToolResult replaceFile(Long id, String path, String hash, String content) {
        return write(id, path, hash, content, "replace");
    }

    private ToolResult write(Long id, String path, String hash, String content, String mode) {
        if (content == null || content.getBytes(StandardCharsets.UTF_8).length > 256 * 1024) {
            return ToolResult.error("TOOL_ARGUMENT_INVALID", "内容不能为空引用或超过 256KB");
        }
        return mutate(id, (session, workspace) ->
            write.write(workspace, path, hash, content, mode));
    }

    public ToolResult patchFile(Long id, String path, String hash, String patch) {
        if (patch == null || patch.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) {
            return ToolResult.error("TOOL_ARGUMENT_INVALID", "补丁不能为空引用或超过 1MiB");
        }
        return mutate(id, (session, workspace) ->
            this.patch.patch(workspace, path, hash, patch));
    }

    public ToolResult editFile(
        Long id, String path, String hash, String oldText, String newText, boolean replaceAll) {
        return mutate(id, (session, workspace) ->
            edit.edit(workspace, path, hash, oldText, newText, replaceAll));
    }

    /**
     * 写入类操作把会话/租约问题也转换为模型可见的失败结果，读取类操作直接抛出。
     */
    private ToolResult mutate(Long id, FileOperation operation) {
        try {
            return execute(id, operation);
        } catch (RuntimeException exception) {
            return ToolResult.error(
                "TOOL_FAILED",
                exception.getMessage() == null ? "Sandbox 执行失败" : exception.getMessage());
        }
    }

    private ToolResult execute(Long id, FileOperation operation) {
        SandboxSession session = sessions.require(id);
        sessions.renewWriteLease(id);
        Path workspace = paths.resolveLocal(session.storageKey());
        try {
            return operation.apply(session, workspace);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ToolResult.error("TOOL_EXECUTION_TIMED_OUT", "工具执行超时或被中断");
        } catch (SandboxException exception) {
            return ToolResult.error(
                exception.getCode(),
                exception.getMessage() == null ? "文件操作失败" : exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return ToolResult.error(
                "TOOL_ARGUMENT_INVALID",
                exception.getMessage() == null ? "工具参数不合法" : exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("sandbox file operation failed workspaceId={}", id, exception);
            return ToolResult.error("TOOL_EXECUTION_FAILED", "工具执行失败");
        }
    }

    @FunctionalInterface
    private interface FileOperation {
        ToolResult apply(SandboxSession session, Path workspace) throws InterruptedException;
    }
}

package qg.qgent.sandbox.execution.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.sandbox.exception.SandboxException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.http.HttpStatus.CONFLICT;

/**
 * 唯一匹配的文本替换；不要求模型生成 Diff 行号，保持原始行尾。
 */
@Component
@RequiredArgsConstructor
public class FileEditTool {
    private static final int MAX_TEXT_BYTES = 256 * 1024;
    private final RepositoryFileResolver files;

    /**
     * 将唯一匹配的 oldText 替换为 newText；replaceAll 为 true 时替换全部匹配。
     */
    public ToolResult edit(
        Path workspace,
        String path,
        String expectedHash,
        String oldText,
        String newText,
        boolean replaceAll) {
        if (path == null || path.isBlank() || path.length() > 1024)
            throw new IllegalArgumentException("工具参数不合法：path");
        if (expectedHash == null || expectedHash.length() != 64)
            throw new IllegalArgumentException("工具参数不合法：expectedHash");
        if (oldText == null || oldText.isEmpty())
            throw new IllegalArgumentException("oldText 不能为空");
        if (oldText.length() > MAX_TEXT_BYTES)
            throw new IllegalArgumentException("工具参数不合法：oldText");
        String replacement = newText == null ? "" : newText;
        if (replacement.length() > MAX_TEXT_BYTES)
            throw new IllegalArgumentException("工具参数不合法：newText");
        var target = files.resolveExisting(workspace, path);
        try {
            if (Files.size(target) > 2 * 1024 * 1024)
                throw new IllegalArgumentException("编辑文件超过 2MiB");
            byte[] bytes = Files.readAllBytes(target);
            if (!FileReadTool.sha256(bytes).equalsIgnoreCase(expectedHash))
                throw new SandboxException(CONFLICT, "FILE_HASH_MISMATCH", "文件已变化，请重新读取后修改");
            String before = Files.readString(target, StandardCharsets.UTF_8);
            // 模型读取窗口中使用 LF，编辑 CRLF 文件时恢复磁盘上的换行。
            if ("CRLF".equals(FileReadTool.detectNewlineStyle(bytes))) {
                oldText = oldText.replace("\r\n", "\n").replace("\n", "\r\n");
                replacement = replacement.replace("\r\n", "\n").replace("\n", "\r\n");
            }
            int position = before.indexOf(oldText);
            if (position < 0)
                throw new SandboxException(CONFLICT, "EDIT_TEXT_NOT_FOUND", "旧文本未匹配，请重新读取并提供准确文本");
            if (!replaceAll && before.indexOf(oldText, position + oldText.length()) >= 0)
                throw new SandboxException(
                    CONFLICT, "EDIT_TEXT_AMBIGUOUS", "旧文本匹配多处，请增加上下文或显式使用 replaceAll");
            String after =
                replaceAll
                    ? before.replace(oldText, replacement)
                    : before.substring(0, position)
                      + replacement
                      + before.substring(position + oldText.length());
            byte[] next = after.getBytes(StandardCharsets.UTF_8);
            if (!before.equals(after)) FileWriteTool.atomicReplace(target, next);
            ToolResult result = ToolResult.ok();
            result.put("path", path);
            result.put("sha256", FileReadTool.sha256(next));
            result.put("changed", !before.equals(after));
            result.put("oldText", oldText);
            result.put("newText", replacement);
            return result;
        } catch (SandboxException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("编辑文件失败：" + exception.getMessage(), exception);
        }
    }
}

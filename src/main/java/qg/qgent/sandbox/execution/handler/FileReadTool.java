package qg.qgent.sandbox.execution.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.harness.tool.ToolResult;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 流式读取 UTF-8 文件，返回有行号的有界窗口和原始字节哈希。
 */
@Component
@RequiredArgsConstructor
public class FileReadTool {
    private static final int MAX_WINDOW_BYTES = 64 * 1024;
    private static final int MAX_LINE_CHARS = 4000;
    private final RepositoryFileResolver files;

    /**
     * 读取窗口；startLine 从 1 开始，只返回本次请求的行数。
     */
    public ToolResult read(Path workspace, String path, int startLine, int lineCount) {
        if (path == null || path.isBlank() || path.length() > 1024)
            throw new IllegalArgumentException("工具参数不合法：path");
        if (startLine < 1) throw new IllegalArgumentException("工具参数不合法：startLine");
        if (lineCount < 1 || lineCount > 1000)
            throw new IllegalArgumentException("工具参数不合法：lineCount");
        Path file = files.resolveExisting(workspace, path);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            boolean hasLf = false, hasCrlf = false;
            int previous = -1;
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int size;
                while ((size = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, size);
                    for (int i = 0; i < size; i++) {
                        int value = buffer[i] & 255;
                        if (value == 10) {
                            hasLf = true;
                            if (previous == 13) hasCrlf = true;
                        }
                        previous = value;
                    }
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                }
            }
            List<Map<String, Object>> lines = new ArrayList<>();
            int total = 0, bytes = 0;
            boolean clipped = false, windowFull = false;
            // 逐字符读取，避免单个超长行先占用无界内存。
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                StringBuilder line = new StringBuilder();
                boolean any = false, lastCr = false, lineClipped = false;
                int value;
                while ((value = reader.read()) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (value == '\n' && lastCr) {
                        lastCr = false;
                        continue;
                    }
                    lastCr = value == '\r';
                    if (value == '\r' || value == '\n') {
                        total++;
                        if (total >= startLine && lines.size() < lineCount && !windowFull) {
                            String text = line.toString();
                            int size = text.getBytes(StandardCharsets.UTF_8).length;
                            if (bytes + size > MAX_WINDOW_BYTES) windowFull = true;
                            else {
                                lines.add(
                                    Map.of(
                                        "number",
                                        total,
                                        "text",
                                        text,
                                        "truncated",
                                        lineClipped));
                                bytes += size;
                                clipped |= lineClipped;
                            }
                        }
                        line.setLength(0);
                        any = false;
                        lineClipped = false;
                    } else {
                        any = true;
                        if (line.length() < MAX_LINE_CHARS) line.append((char) value);
                        else lineClipped = true;
                    }
                }
                if (any) {
                    total++;
                    if (total >= startLine && lines.size() < lineCount && !windowFull) {
                        String text = line.toString();
                        if (bytes + text.getBytes(StandardCharsets.UTF_8).length
                            <= MAX_WINDOW_BYTES) {
                            lines.add(
                                Map.of(
                                    "number",
                                    total,
                                    "text",
                                    text,
                                    "truncated",
                                    lineClipped));
                            clipped |= lineClipped;
                        }
                    }
                }
            }
            int next = startLine + lines.size();
            ToolResult result = ToolResult.ok();
            result.put("path", path);
            result.put("sha256", HexFormat.of().formatHex(digest.digest()));
            result.put("startLine", startLine);
            result.put("totalLines", total);
            result.put("lines", lines);
            result.put("truncated", clipped || next <= total);
            result.put("nextLine", next);
            result.put("endsWithNewline", previous == 10);
            result.put("newlineStyle", hasCrlf ? "CRLF" : hasLf ? "LF" : "NONE");
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读取已取消", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("读取 UTF-8 文件失败：" + exception.getMessage(), exception);
        }
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException("计算文件哈希失败", exception);
        }
    }

    static String detectNewlineStyle(byte[] bytes) {
        boolean lf = false;
        for (int i = 0; i < bytes.length; i++)
            if (bytes[i] == 10) {
                if (i > 0 && bytes[i - 1] == 13) return "CRLF";
                lf = true;
            }
        return lf ? "LF" : "NONE";
    }
}

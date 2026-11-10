package qg.qgent.memory.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

/** 记忆模块公共小工具：正文版本、模型输出整理，以及后台任务共用的 UTC 时间与失败摘要。 */
final class MemorySupport {

    private MemorySupport() {
    }

    /** 记忆内容版本，用于并发修改检测和重复发现判断。 */
    static String version(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 模型偶尔仍会套代码围栏，写入前去掉，避免记忆正文被围栏污染。 */
    static String withoutCodeFence(String text) {
        String trimmed = text.strip();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        return firstLineEnd < 0 || lastFence <= firstLineEnd ? trimmed
                : trimmed.substring(firstLineEnd + 1, lastFence).strip();
    }

    static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 失败摘要，截断到状态表列宽。 */
    static String failure(RuntimeException exception) {
        String message = exception.getMessage();
        String text = exception.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
        return text.length() <= 500 ? text : text.substring(0, 500);
    }
}

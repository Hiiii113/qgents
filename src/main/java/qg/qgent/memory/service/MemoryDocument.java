package qg.qgent.memory.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import qg.qgent.common.web.ApiException;

/**
 * 三种记忆共用的一份 memory.md：项目、群和 Agent 私有记忆只是所在目录不同，读写策略相同。
 * <p>
 * 超出上下文字符预算时拒绝写入，不截断；写入先落临时文件再原子替换，避免进程中断留下半篇记忆。
 */
@Component
public class MemoryDocument {

    private static final String FILE_NAME = "memory.md";

    private final int maxChars;

    public MemoryDocument(@Value("${app.agent.context.max-memory-chars:12000}") int maxChars) {
        this.maxChars = maxChars;
    }

    /** 读取正文；文件不存在时为空。 */
    public String read(Path directory) {
        Path file = memoryFile(directory);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException missing) {
            return "";
        } catch (IOException e) {
            throw new IllegalStateException("记忆读取失败: " + file, e);
        }
    }

    /** 最后修改时间（UTC）；文件不存在时为空。 */
    public LocalDateTime updatedAt(Path directory) {
        Path file = memoryFile(directory);
        try {
            return LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(file).toInstant(), ZoneOffset.UTC);
        } catch (NoSuchFileException missing) {
            return null;
        } catch (IOException e) {
            throw new IllegalStateException("记忆读取失败: " + file, e);
        }
    }

    /** 整篇替换；超出字符预算时拒绝写入，原文件保持不变。 */
    public void write(Path directory, String content) {
        if (content.length() > maxChars) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MEMORY_CONTEXT_LIMIT_EXCEEDED",
                    "记忆超出 Agent 上下文字符预算 " + maxChars + "，请精简后重新写入");
        }
        Path file = memoryFile(directory);
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, FILE_NAME, ".tmp");
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("记忆写入失败: " + file, e);
        }
    }

    /** 正文哈希；后台发现用它判断群记忆是否变化。 */
    public String version(String content) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 注入 Agent 上下文的记忆字符预算；后台提示词与写入校验共用这一处配置。 */
    public int maxChars() {
        return maxChars;
    }

    private Path memoryFile(Path directory) {
        return directory.resolve(FILE_NAME);
    }
}

package qg.qgent.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * groups/&lt;groupId&gt;/memory-meta.json 的读写：与 memory.md 同目录，只由程序读写。
 * <p>
 * 文件不存在时按默认值返回；写入先落临时文件再原子替换，与 {@link MemoryDocument} 的策略一致。
 * 序列化固定为 ISO-8601 时间，不随接口层的 Jackson 配置变化。
 */
@Component
public class GroupMemoryMetaStore {

    private static final String FILE_NAME = "memory-meta.json";

    private final ObjectMapper json =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * 读取元数据；尚未写入过时返回默认值。
     */
    public GroupMemoryMeta read(Path directory) {
        Path file = metaFile(directory);
        try {
            return json.readValue(
                Files.readString(file, StandardCharsets.UTF_8), GroupMemoryMeta.class);
        } catch (NoSuchFileException missing) {
            return GroupMemoryMeta.initial();
        } catch (IOException e) {
            throw new IllegalStateException("群记忆元数据读取失败: " + file, e);
        }
    }

    /**
     * 整篇替换元数据；调用方负责先持有群级锁。
     */
    public void write(Path directory, GroupMemoryMeta meta) {
        Path file = metaFile(directory);
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, FILE_NAME, ".tmp");
            Files.writeString(temporary, json.writeValueAsString(meta), StandardCharsets.UTF_8);
            Files.move(
                temporary,
                file,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("群记忆元数据写入失败: " + file, e);
        }
    }

    private Path metaFile(Path directory) {
        return directory.resolve(FILE_NAME);
    }
}

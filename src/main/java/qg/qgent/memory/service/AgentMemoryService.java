package qg.qgent.memory.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import qg.qgent.common.web.ApiException;
import qg.qgent.project.storage.ProjectDirectoryService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Agent 私有记忆：每个 Agent 在项目目录下只有一篇，它所在的各个群共享这一篇，只能由它自己维护。 */
@Service
public class AgentMemoryService {

    private static final String FILE_NAME = "memory.md";

    private final ProjectDirectoryService directories;
    private final int maxMemoryChars;

    public AgentMemoryService(ProjectDirectoryService directories,
                              @Value("${app.agent.context.max-memory-chars:12000}") int maxMemoryChars) {
        this.directories = directories;
        this.maxMemoryChars = maxMemoryChars;
    }

    public String view(Long projectId, Long agentId) {
        Path file = file(projectId, agentId);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException missing) {
            return "";
        } catch (IOException e) {
            throw new IllegalStateException("Agent 私有记忆读取失败: " + file, e);
        }
    }

    public void save(Long projectId, Long agentId, String content) {
        String text = content == null ? "" : content;
        if (text.length() > maxMemoryChars) {
            throw new ApiException(HttpStatus.CONFLICT, "MEMORY_CONTEXT_LIMIT_EXCEEDED",
                    "Agent 私有记忆超出 Agent 上下文字符预算 " + maxMemoryChars + "，请精简后保存");
        }
        Path file = file(projectId, agentId);
        try {
            Files.createDirectories(file.getParent());
            // 先写临时文件再原子替换，避免进程中断留下半篇记忆。
            Path temporary = Files.createTempFile(file.getParent(), "memory", ".tmp");
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Agent 私有记忆写入失败: " + file, e);
        }
    }

    private Path file(Long projectId, Long agentId) {
        return directories.agentMemory(projectId, agentId).resolve(FILE_NAME);
    }
}

package qg.qgent.memory.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import qg.qgent.chat.service.GroupService;
import qg.qgent.common.web.ApiException;
import qg.qgent.memory.dto.AgentMemoryView;
import qg.qgent.project.storage.ProjectDirectoryService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Agent 私有记忆：项目目录下按 Agent 和来源群分文件，只能由本人维护，且只在来源群受众覆盖当前群时注入。
 * <p>
 * 分文件而不是单篇，是为了让受限群写下的内容无法以已合并的正文进入受众更广的群。
 */
@Service
public class AgentMemoryService {

    private static final String SUFFIX = ".md";

    private final GroupService groups;
    private final ProjectDirectoryService directories;
    private final int maxMemoryChars;

    public AgentMemoryService(GroupService groups, ProjectDirectoryService directories,
                              @Value("${app.agent.context.max-memory-chars:12000}") int maxMemoryChars) {
        this.groups = groups;
        this.directories = directories;
        this.maxMemoryChars = maxMemoryChars;
    }

    /** 当前群可改写的正文，以及来源群受众覆盖当前群、只能读取的部分。 */
    public AgentMemoryView view(Long projectId, Long agentId, Long groupId) {
        return new AgentMemoryView(read(projectId, agentId, groupId), inherited(projectId, agentId, groupId));
    }

    /** 覆盖当前群可改写的正文；与继承部分合计不超上下文字符预算。 */
    public void save(Long projectId, Long agentId, Long groupId, String content) {
        String text = content == null ? "" : content;
        if (new AgentMemoryView(text, inherited(projectId, agentId, groupId)).merged().length() > maxMemoryChars) {
            throw new ApiException(HttpStatus.CONFLICT, "MEMORY_CONTEXT_LIMIT_EXCEEDED",
                    "Agent 私有记忆超出 Agent 上下文字符预算 " + maxMemoryChars + "，请精简后保存");
        }
        // 按群分文件，因此不同群并发写入互不影响；同一群的两个运行并发写入时最后写入生效。
        write(projectId, agentId, groupId, text);
    }

    /** 来源群成员包含当前群成员时才继承，避免受限群内容流向受众更广的群。 */
    private String inherited(Long projectId, Long agentId, Long groupId) {
        Path directory = directories.agentMemory(projectId, agentId);
        if (Files.notExists(directory)) {
            return "";
        }
        try (Stream<Path> files = Files.list(directory)) {
            List<Long> sources = files
                    .filter(file -> file.getFileName().toString().endsWith(SUFFIX))
                    .map(AgentMemoryService::sourceGroup).sorted().toList();
            return sources.stream()
                    .filter(source -> !source.equals(groupId))
                    .filter(source -> groups.audienceCoveredBy(projectId, source, groupId))
                    .map(source -> read(projectId, agentId, source))
                    .filter(text -> !text.isBlank())
                    .collect(Collectors.joining("\n\n"));
        } catch (IOException e) {
            throw new IllegalStateException("Agent 私有记忆目录读取失败: " + directory, e);
        }
    }

    private static Long sourceGroup(Path file) {
        String name = file.getFileName().toString();
        return Long.valueOf(name.substring(0, name.length() - SUFFIX.length()));
    }

    private String read(Long projectId, Long agentId, Long sourceGroupId) {
        Path file = file(projectId, agentId, sourceGroupId);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException missing) {
            return "";
        } catch (IOException e) {
            throw new IllegalStateException("Agent 私有记忆读取失败: " + file, e);
        }
    }

    private void write(Long projectId, Long agentId, Long groupId, String content) {
        Path file = file(projectId, agentId, groupId);
        try {
            Files.createDirectories(file.getParent());
            // 先写临时文件再原子替换，避免进程中断留下半篇记忆。
            Path temporary = Files.createTempFile(file.getParent(), groupId.toString(), ".tmp");
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Agent 私有记忆写入失败: " + file, e);
        }
    }

    private Path file(Long projectId, Long agentId, Long sourceGroupId) {
        return directories.agentMemory(projectId, agentId).resolve(sourceGroupId + SUFFIX);
    }
}

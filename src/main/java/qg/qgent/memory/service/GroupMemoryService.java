package qg.qgent.memory.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import qg.qgent.chat.service.GroupService;
import qg.qgent.memory.dto.GroupMemoryResponse;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.project.storage.ProjectDirectoryService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 群聊记忆：每个群在项目目录 groups/&lt;groupId&gt;/memory.md 保存独立正文，只由后台提炼任务写入。 */
@Service
public class GroupMemoryService {

    private static final String FILE_NAME = "memory.md";

    private final ProjectAccessService access;
    private final GroupService groups;
    private final ProjectDirectoryService directories;
    private final int maxMemoryChars;

    public GroupMemoryService(ProjectAccessService access, GroupService groups, ProjectDirectoryService directories,
                              @Value("${app.agent.context.max-memory-chars:12000}") int maxMemoryChars) {
        this.access = access;
        this.groups = groups;
        this.directories = directories;
        this.maxMemoryChars = maxMemoryChars;
    }

    /** 管理员只读查看：项目管理员身份和来源群成员身份必须同时满足。 */
    public GroupMemoryResponse get(Long actor, Long projectId, Long groupId) {
        access.requireProjectAdmin(projectId, actor);
        groups.requireGroupMember(projectId, groupId, actor);
        return read(projectId, groupId);
    }

    /** 读取群记忆；尚未提炼时返回空正文，不创建文件。 */
    public GroupMemoryResponse read(Long projectId, Long groupId) {
        Path file = memoryFile(projectId, groupId);
        if (Files.notExists(file)) {
            return new GroupMemoryResponse(id(projectId), id(groupId), "", null);
        }
        try {
            return new GroupMemoryResponse(id(projectId), id(groupId), Files.readString(file, StandardCharsets.UTF_8),
                    LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneOffset.UTC));
        } catch (IOException e) {
            throw new IllegalStateException("群记忆读取失败: " + file, e);
        }
    }

    /** 供后台提炼写入整篇群记忆；超出上下文预算时截断尾部。 */
    public void write(Long projectId, Long groupId, String content) {
        String text = content == null ? "" : content;
        // shortcut: 超出上下文预算时截断尾部，模型按提示中的字数上限生成
        if (text.length() > maxMemoryChars) {
            text = text.substring(0, maxMemoryChars);
        }
        Path file = memoryFile(projectId, groupId);
        try {
            Files.createDirectories(file.getParent());
            // 先写临时文件再原子替换，避免进程中断留下半篇群记忆
            Path temporary = Files.createTempFile(file.getParent(), FILE_NAME, ".tmp");
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("群记忆写入失败: " + file, e);
        }
    }

    private Path memoryFile(Long projectId, Long groupId) {
        return directories.projectRoot(projectId).resolve("groups").resolve(groupId.toString()).resolve(FILE_NAME);
    }

    private String id(Long value) {
        return value == null ? null : value.toString();
    }
}

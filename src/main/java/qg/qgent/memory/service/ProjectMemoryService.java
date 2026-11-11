package qg.qgent.memory.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.common.web.ApiException;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.realtime.service.EventService;

/**
 * 项目长期记忆：项目目录下的 memory.md 是正文唯一来源，按内容版本检测并发修改。
 * <p>
 * 只有项目管理员在项目默认群的对话中确认后才写入，其余成员和子 Agent 只读。
 */
@Service
public class ProjectMemoryService {

    private static final String FILE_NAME = "memory.md";

    private final ProjectAccessService access;
    private final ChatGroupMapper groups;
    private final ProjectMapper projects;
    private final ProjectDirectoryService directories;
    private final EventService events;
    private final int maxMemoryChars;

    public ProjectMemoryService(
            ProjectAccessService access,
            ChatGroupMapper groups,
            ProjectMapper projects,
            ProjectDirectoryService directories,
            EventService events,
            @Value("${app.agent.context.max-memory-chars:12000}") int maxMemoryChars) {
        this.access = access;
        this.groups = groups;
        this.projects = projects;
        this.directories = directories;
        this.events = events;
        this.maxMemoryChars = maxMemoryChars;
    }

    /** 读取项目记忆；项目首次访问时创建空 memory.md。 */
    public ProjectMemoryResponse get(Long actor, Long projectId) {
        access.requireProjectMember(projectId, actor);
        return read(projectId);
    }

    /**
     * 管理员在默认群确认后整篇替换 memory.md。
     * <p>
     * 先锁项目行，让同项目的写入跨实例串行；baseVersion 与当前内容不一致时拒绝，避免覆盖他人已写入的更新；
     * 内容与当前相同时直接返回，使写入重试可以幂等完成。
     */
    @Transactional
    public ProjectMemoryResponse update(
            Long actor, Long projectId, Long groupId, String content, String baseVersion) {
        access.requireProjectAdmin(projectId, actor);
        requireDefaultGroup(groupId);
        String text = content.trim();
        requireContentBudget(text);
        projects.selectByIdForUpdate(projectId);
        ProjectMemoryResponse current = read(projectId);
        if (current.getContent().equals(text)) {
            return current;
        }
        if (!current.getVersion().equals(baseVersion)) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "MEMORY_VERSION_CONFLICT", "项目记忆已被其他更新修改，请重新读取后合并");
        }
        write(projectId, text);
        ProjectMemoryResponse saved = read(projectId);
        events.publish(
                projectId,
                null,
                "memory.updated",
                projectId.toString(),
                Map.of(
                        "projectId",
                        projectId.toString(),
                        "version",
                        saved.getVersion(),
                        "updatedAt",
                        saved.getUpdatedAt().toString()));
        return saved;
    }

    /** 读取正文与版本；不做权限检查，只给已通过成员校验的调用方和后台发现任务使用。 */
    ProjectMemoryResponse read(Long projectId) {
        Path file = memoryFile(projectId);
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return new ProjectMemoryResponse(
                    projectId.toString(),
                    content,
                    MemorySupport.version(content),
                    LocalDateTime.ofInstant(
                            Files.getLastModifiedTime(file).toInstant(), ZoneOffset.UTC));
        } catch (IOException e) {
            throw new IllegalStateException("项目记忆读取失败: " + file, e);
        }
    }

    /** 只在项目默认群写入：受限群的讨论不扩散为共享项目记忆。 */
    private void requireDefaultGroup(Long groupId) {
        ChatGroupEntity group = groups.selectById(groupId);
        if (group == null || !Boolean.TRUE.equals(group.getDefaultGroup())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "MEMORY_SOURCE_GROUP_RESTRICTED", "只能在项目默认群更新项目记忆");
        }
    }

    /** 正文必须与注入 Agent 上下文的记忆预算一致，超限时拒绝写入。 */
    private void requireContentBudget(String content) {
        if (content.length() > maxMemoryChars) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MEMORY_CONTEXT_LIMIT_EXCEEDED",
                    "项目记忆超出 Agent 上下文字符预算 " + maxMemoryChars + "，请精简后重新写入");
        }
    }

    private void write(Long projectId, String content) {
        Path file = memoryFile(projectId);
        try {
            // 先写临时文件再原子替换，避免进程中断留下半篇记忆。
            Path temporary = Files.createTempFile(file.getParent(), FILE_NAME, ".tmp");
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("项目记忆写入失败: " + file, e);
        }
    }

    /** memory.md 路径；不存在时创建空文件。 */
    private Path memoryFile(Long projectId) {
        Path file = directories.projectRoot(projectId).resolve(FILE_NAME);
        if (Files.notExists(file)) {
            try {
                Files.createFile(file);
            } catch (FileAlreadyExistsException ignored) {
                // 并发首次访问时另一个请求已创建
            } catch (IOException e) {
                throw new IllegalStateException("项目记忆创建失败: " + file, e);
            }
        }
        return file;
    }
}

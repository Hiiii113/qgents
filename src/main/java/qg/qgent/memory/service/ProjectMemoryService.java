package qg.qgent.memory.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import qg.qgent.common.web.ApiException;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.project.storage.ProjectDirectoryService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 项目长期记忆：项目目录下的 memory.md 是正文唯一来源，按内容版本检测并发修改。
 * <p>
 * 写入只发生在已审批的项目记忆候选上，不再提供直接保存的入口。
 */
@Service
public class ProjectMemoryService {

    private static final String FILE_NAME = "memory.md";

    private final ProjectAccessService access;
    private final ProjectDirectoryService directories;
    private final EventService events;

    // 同一实例内的写入串行化；跨实例由审批方持有的项目行锁与内容版本兜底。
    private final ConcurrentMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final int maxMemoryChars;

    public ProjectMemoryService(ProjectAccessService access, ProjectDirectoryService directories,
                                EventService events,
                                @Value("${app.agent.context.max-memory-chars:12000}") int maxMemoryChars) {
        this.access = access;
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
     * 审批通过后整篇替换 memory.md。
     * <p>
     * baseVersion 与当前内容不一致时拒绝，避免覆盖候选提出后其他人已审批的更新；
     * 内容与候选相同时直接返回，使审批重试（例如写文件后进程中断）可以幂等完成。
     */
    public ProjectMemoryResponse applyApproved(Long projectId, String content, String baseVersion) {
        ReentrantLock lock = locks.computeIfAbsent(projectId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            ProjectMemoryResponse current = read(projectId);
            if (current.getContent().equals(content)) {
                return current;
            }
            if (!current.getVersion().equals(baseVersion)) {
                throw new ApiException(HttpStatus.CONFLICT, "MEMORY_VERSION_CONFLICT",
                        "项目记忆在候选提出后已被修改，请重新提出候选");
            }
            write(projectId, content);
            ProjectMemoryResponse saved = read(projectId);
            events.publish(projectId, null, "memory.updated", projectId.toString(),
                    Map.of("projectId", projectId.toString(), "version", saved.getVersion(),
                            "updatedAt", saved.getUpdatedAt().toString()));
            return saved;
        } finally {
            lock.unlock();
        }
    }

    /** 候选正文必须与注入 Agent 上下文的记忆预算一致；超限时拒绝提出，而不是等到审批才失败。 */
    public void requireContentBudget(String content) {
        if (content.length() > maxMemoryChars) {
            throw new ApiException(HttpStatus.CONFLICT, "MEMORY_CONTEXT_LIMIT_EXCEEDED",
                    "项目记忆超出 Agent 上下文字符预算 " + maxMemoryChars + "，请精简候选后重新提出");
        }
    }

    /** 读取正文与版本；不做权限检查，只给已通过成员校验的调用方和后台发现任务使用。 */
    ProjectMemoryResponse read(Long projectId) {
        Path file = memoryFile(projectId);
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return new ProjectMemoryResponse(projectId.toString(), content, MemoryText.version(content),
                    LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneOffset.UTC));
        } catch (IOException e) {
            throw new IllegalStateException("项目记忆读取失败: " + file, e);
        }
    }

    private void write(Long projectId, String content) {
        Path file = memoryFile(projectId);
        try {
            // 先写临时文件再原子替换，避免进程中断留下半篇记忆。
            Path temporary = Files.createTempFile(file.getParent(), FILE_NAME, ".tmp");
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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

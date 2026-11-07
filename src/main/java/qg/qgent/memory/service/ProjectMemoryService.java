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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** 项目长期记忆：项目目录下的 memory.md 是正文唯一来源，按内容版本检测并发修改。 */
@Service
public class ProjectMemoryService {

    private static final String FILE_NAME = "memory.md";

    private final ProjectAccessService access;
    private final ProjectDirectoryService directories;
    private final EventService events;

    // 同一实例内的写入串行化；跨实例并发由版本检测兜底。
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

    /** 整篇替换项目记忆；expectedVersion 与当前内容不一致时拒绝，避免静默覆盖他人编辑。 */
    public ProjectMemoryResponse save(Long actor, Long projectId, String content, String expectedVersion) {
        access.requireProjectMember(projectId, actor);
        String text = content == null ? "" : content;
        if (text.length() > maxMemoryChars) {
            throw new ApiException(HttpStatus.CONFLICT, "MEMORY_CONTEXT_LIMIT_EXCEEDED",
                    "项目记忆超出 Agent 上下文字符预算 " + maxMemoryChars + "，请精简后保存");
        }
        ReentrantLock lock = locks.computeIfAbsent(projectId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            ProjectMemoryResponse current = read(projectId);
            if (!current.getVersion().equals(expectedVersion)) {
                throw new ApiException(HttpStatus.CONFLICT, "MEMORY_VERSION_CONFLICT",
                        "项目记忆已被修改，请重新读取后再保存");
            }
            write(projectId, text);
            ProjectMemoryResponse saved = read(projectId);
            events.publish(projectId, null, "memory.updated", projectId.toString(),
                    Map.of("projectId", projectId.toString(), "version", saved.getVersion(),
                            "updatedAt", saved.getUpdatedAt().toString()));
            return saved;
        } finally {
            lock.unlock();
        }
    }

    private ProjectMemoryResponse read(Long projectId) {
        Path file = memoryFile(projectId);
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return new ProjectMemoryResponse(projectId.toString(), content, version(content),
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

    private String version(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

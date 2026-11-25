package qg.qgent.memory.service;

import org.springframework.stereotype.Service;
import qg.qgent.chat.service.GroupService;
import qg.qgent.memory.dto.GroupMemoryResponse;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.project.storage.ProjectDirectoryService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 群聊记忆：每个群在项目目录 groups/&lt;groupId&gt;/memory.md 保存独立正文，
 * 同目录的 memory-meta.json 记录 revision 和提炼进度。
 * <p>
 * 同一群的读改写由进程内的群级锁串行；后台提炼再以 revision 做乐观锁，
 * 提炼期间 Agent 改过正文时放弃旧结果，不覆盖新改动。
 */
@Service
public class GroupMemoryService {

    private final ProjectAccessService access;
    private final GroupService groups;
    private final ProjectDirectoryService directories;
    private final MemoryDocument document;
    private final GroupMemoryMetaStore metaStore;
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public GroupMemoryService(
        ProjectAccessService access,
        GroupService groups,
        ProjectDirectoryService directories,
        MemoryDocument document,
        GroupMemoryMetaStore metaStore) {
        this.access = access;
        this.groups = groups;
        this.directories = directories;
        this.document = document;
        this.metaStore = metaStore;
    }

    /**
     * 管理员只读查看：项目管理员身份和来源群成员身份必须同时满足。
     */
    public GroupMemoryResponse get(Long actor, Long projectId, Long groupId) {
        access.requireProjectAdmin(projectId, actor);
        // 私聊没有群记忆，也不给管理员留越过双方的入口。
        groups.requireChatGroup(projectId, groupId, actor);
        return read(projectId, groupId);
    }

    /**
     * 读取群记忆；尚未提炼时返回空正文。
     */
    public GroupMemoryResponse read(Long projectId, Long groupId) {
        Path directory = directories.groupRoot(projectId, groupId);
        return new GroupMemoryResponse(
            id(projectId),
            id(groupId),
            document.read(directory),
            document.updatedAt(directory));
    }

    /**
     * 读取正文和当前 revision；两者在同一把群锁内读取，后台提炼据此做乐观锁比对。
     */
    public GroupMemorySnapshot readWithRevision(Long projectId, Long groupId) {
        Path directory = directories.groupRoot(projectId, groupId);
        ReentrantLock lock = lock(groupId);
        lock.lock();
        try {
            return new GroupMemorySnapshot(
                document.read(directory),
                metaStore.read(directory).revision(),
                document.updatedAt(directory));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Agent 按片段改写群记忆：持有群锁，遵循唯一锚点规则，写入后 revision 自增。
     *
     * @return 写入后的 revision
     */
    public long edit(Long projectId, Long groupId, String anchor, String replacement) {
        Path directory = directories.groupRoot(projectId, groupId);
        ReentrantLock lock = lock(groupId);
        lock.lock();
        try {
            document.edit(directory, anchor, replacement);
            return bumpRevision(directory);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Agent 整篇重写群记忆：持有群锁，超出上下文预算时拒绝写入，成功后 revision 自增。
     *
     * @return 写入后的 revision
     */
    public long writeByAgent(Long projectId, Long groupId, String content) {
        Path directory = directories.groupRoot(projectId, groupId);
        ReentrantLock lock = lock(groupId);
        lock.lock();
        try {
            document.write(directory, content);
            return bumpRevision(directory);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 后台提炼写回：持有群锁，只有当前 revision 仍是提炼开始时读到的版本才写入。
     * <p>
     * 版本不一致说明提炼期间 Agent 改过正文，本次结果作废并返回 false；
     * 版本一致时同时推进提炼水位 processedThrough。
     */
    public boolean writeIfRevisionMatches(
        Long projectId,
        Long groupId,
        long expectedRevision,
        String newContent,
        long processedThrough) {
        Path directory = directories.groupRoot(projectId, groupId);
        ReentrantLock lock = lock(groupId);
        lock.lock();
        try {
            GroupMemoryMeta current = metaStore.read(directory);
            if (current.revision() != expectedRevision) {
                return false;
            }
            document.write(directory, newContent);
            metaStore.write(directory, current.written(processedThrough));
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 调度元数据；文件不存在时返回默认值。只读快照，写入统一走本类的群锁方法。
     */
    public GroupMemoryMeta meta(Long projectId, Long groupId) {
        return metaStore.read(directories.groupRoot(projectId, groupId));
    }

    /**
     * 项目记忆发现完成后记录读过的群记忆版本和发现时间，不改变正文版本。
     */
    public void markDiscovered(Long projectId, Long groupId, String version, Instant at) {
        Path directory = directories.groupRoot(projectId, groupId);
        ReentrantLock lock = lock(groupId);
        lock.lock();
        try {
            metaStore.write(directory, metaStore.read(directory).discovered(version, at));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 群成员校验：读写群记忆的工具都要先经过这里，沿用群聊的成员规则。
     */
    public void requireMember(Long actor, Long projectId, Long groupId) {
        groups.requireChatGroup(projectId, groupId, actor);
    }

    private long bumpRevision(Path directory) {
        GroupMemoryMeta current = metaStore.read(directory);
        GroupMemoryMeta written = current.rewritten();
        metaStore.write(directory, written);
        return written.revision();
    }

    /**
     * 群级锁：同一群的读改写串行；按群 ID 懒创建，进程内有效。
     */
    private ReentrantLock lock(Long groupId) {
        return locks.computeIfAbsent(groupId, ignored -> new ReentrantLock());
    }

    private String id(Long value) {
        return value == null ? null : value.toString();
    }
}

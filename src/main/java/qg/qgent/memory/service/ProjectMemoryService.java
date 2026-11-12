package qg.qgent.memory.service;

import java.nio.file.Path;
import java.util.Map;
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
 * 项目长期记忆：项目目录下的 memory.md 是正文唯一来源，整篇替换。
 * <p>
 * 只有项目管理员在项目默认群的对话中确认后才写入，其余成员和子 Agent 只读。
 */
@Service
public class ProjectMemoryService {

    private final ProjectAccessService access;
    private final ChatGroupMapper groups;
    private final ProjectMapper projects;
    private final ProjectDirectoryService directories;
    private final EventService events;
    private final MemoryDocument document;

    public ProjectMemoryService(
            ProjectAccessService access,
            ChatGroupMapper groups,
            ProjectMapper projects,
            ProjectDirectoryService directories,
            EventService events,
            MemoryDocument document) {
        this.access = access;
        this.groups = groups;
        this.projects = projects;
        this.directories = directories;
        this.events = events;
        this.document = document;
    }

    /** 读取项目记忆；项目尚未写入时返回空正文。 */
    public ProjectMemoryResponse get(Long actor, Long projectId) {
        access.requireProjectMember(projectId, actor);
        return read(projectId);
    }

    /**
     * 管理员在默认群确认后整篇替换 memory.md。
     * <p>
     * 先锁项目行，让同项目的写入跨实例串行；内容与当前相同时直接返回，使写入重试可以幂等完成。
     */
    @Transactional
    public ProjectMemoryResponse update(Long actor, Long projectId, Long groupId, String content) {
        access.requireProjectAdmin(projectId, actor);
        requireDefaultGroup(groupId);
        String text = content.trim();
        projects.selectByIdForUpdate(projectId);
        ProjectMemoryResponse current = read(projectId);
        if (current.getContent().equals(text)) {
            return current;
        }
        document.write(directories.projectRoot(projectId), text);
        ProjectMemoryResponse saved = read(projectId);
        events.publish(
                projectId,
                null,
                "memory.updated",
                projectId.toString(),
                Map.of(
                        "projectId",
                        projectId.toString(),
                        "updatedAt",
                        saved.getUpdatedAt().toString()));
        return saved;
    }

    /** 读取正文与最后修改时间；不做权限检查，只给已通过成员校验的调用方和后台发现任务使用。 */
    ProjectMemoryResponse read(Long projectId) {
        Path directory = directories.projectRoot(projectId);
        return new ProjectMemoryResponse(
                projectId.toString(), document.read(directory), document.updatedAt(directory));
    }

    /** 只在项目默认群写入：受限群的讨论不扩散为共享项目记忆。 */
    private void requireDefaultGroup(Long groupId) {
        ChatGroupEntity group = groups.selectById(groupId);
        if (group == null || !Boolean.TRUE.equals(group.getDefaultGroup())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "MEMORY_SOURCE_GROUP_RESTRICTED", "只能在项目默认群更新项目记忆");
        }
    }
}

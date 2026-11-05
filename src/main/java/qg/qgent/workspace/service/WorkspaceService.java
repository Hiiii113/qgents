package qg.qgent.workspace.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.mapper.WorkspaceMapper;

import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 管理工作区记录；实际目录及沙箱由 Sandbox 准备。 */
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceMapper workspaceMapper;

    /** 存储键使用随机 UUID，避免把自增 ID 暴露到目录名和容器标签。 */
    public WorkspaceEntity create(Long projectId) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setProjectId(projectId);
        workspace.setStorageKey("workspaces/" + UUID.randomUUID());
        workspace.setStatus("PROVISIONING");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    public List<WorkspaceEntity> list(Long projectId) {
        return workspaceMapper.selectList(Wrappers.<WorkspaceEntity>lambdaQuery()
                .eq(WorkspaceEntity::getProjectId, projectId)
                .orderByDesc(WorkspaceEntity::getUpdatedAt).last("LIMIT 100"));
    }

    public WorkspaceEntity require(Long projectId, Long workspaceId) {
        WorkspaceEntity workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null || !projectId.equals(workspace.getProjectId())
                || "ARCHIVED".equals(workspace.getStatus()))
            throw new IllegalArgumentException("工作区不存在或不可用");
        return workspace;
    }

}

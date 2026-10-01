package qg.qgent.workspace.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import qg.qgent.common.util.UuidV7;

import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.mapper.WorkspaceMapper;

import java.util.UUID;
import java.util.List;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 管理工作区记录；实际目录及沙箱由 Worker 准备。 */
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceMapper workspaceMapper;

    public WorkspaceEntity create(UUID projectId) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(UuidV7.next());
        workspace.setProjectId(projectId);
        workspace.setStorageKey("workspaces/" + workspace.getId());
        workspace.setStatus("PROVISIONING");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    public List<WorkspaceEntity> list(UUID projectId) {
        return workspaceMapper.selectList(Wrappers.<WorkspaceEntity>lambdaQuery()
                .eq(WorkspaceEntity::getProjectId, projectId)
                .orderByDesc(WorkspaceEntity::getUpdatedAt).last("LIMIT 100"));
    }

    public WorkspaceEntity require(UUID projectId, UUID workspaceId) {
        WorkspaceEntity workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null || !projectId.equals(workspace.getProjectId())
                || "ARCHIVED".equals(workspace.getStatus()))
            throw new IllegalArgumentException("工作区不存在或不可用");
        return workspace;
    }

}

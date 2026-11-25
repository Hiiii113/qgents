package qg.qgent.workspace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目中的持久化工作目录；目录名由项目 ID 与是否默认决定，见 WorkspacePaths。
 */
@Data
@TableName("workspaces")
public class WorkspaceEntity {
    // 工作区 ID。
    @TableId(type = IdType.AUTO)
    private Long id;

    // 所属项目。
    private Long projectId;
    // 是否为项目默认 Workspace；每个项目恰好一个。
    private Boolean isDefault;
    // 创建时间（UTC）。
    private LocalDateTime createdAt;
    // 更新时间（UTC）。
    private LocalDateTime updatedAt;
}

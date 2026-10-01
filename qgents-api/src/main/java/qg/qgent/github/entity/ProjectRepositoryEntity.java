package qg.qgent.github.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.UUID;

/** 项目显式导入的仓库；与 Workspace 的生命周期无关。 */
@Data
@TableName("project_repositories")
public class ProjectRepositoryEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID projectId;
    private UUID installationId;
    private Long providerRepositoryId;
    private String fullName;
    private String defaultBranch;
    private String htmlUrl;
    private Boolean privateRepository;
}

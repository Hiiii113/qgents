package qg.qgent.project.mapper.view;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class ProjectMembershipView {
    private UUID id;
    private UUID teamId;
    private String name;
    private String description;
    private String role;
    private String status;
    private Long memberCount;
    /** 项目头像 URL（可为空）。 */
    private String avatarUrl;
    /** 项目最后活跃时间（项目群聊的最近消息时间；无消息时为空），仅按活跃排序查询返回。 */
    private LocalDateTime lastActivityAt;
}

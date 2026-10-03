package qg.qgent.project.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 项目资料、当前用户角色、成员数和最近聊天活跃时间。 */
@Data
@NoArgsConstructor
public class ProjectResponse {
    private String id;
    private String teamId;
    private String name;
    private String description;
    private String role;
    private String status;

    // 项目成员数（project_members 计数），项目列表/详情接口返回。
    @Schema(description = "项目成员数")
    private Long memberCount;

    // 项目最后活跃时间（ISO8601 UTC）：该项目下所有群中最近一条消息时间的最大值； 任何群都无消息时为 null（沉底）。供「按最后活跃排序」的项目列表返回。
    @Schema(description = "项目最后活跃时间（ISO8601 UTC）")
    private String lastActivityAt;

    // 项目头像 URL（OSS 公共读长期地址，可为空）。
    @Schema(description = "项目头像 URL")
    private String avatarUrl;

    public ProjectResponse(String id, String teamId, String name, String description, String role, String status,
                           Long memberCount) {
        this.id = id;
        this.teamId = teamId;
        this.name = name;
        this.description = description;
        this.role = role;
        this.status = status;
        this.memberCount = memberCount;
    }
}

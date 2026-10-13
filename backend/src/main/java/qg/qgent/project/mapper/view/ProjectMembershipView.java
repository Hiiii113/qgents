package qg.qgent.project.mapper.view;

import lombok.Data;

import java.util.UUID;

@Data
public class ProjectMembershipView {
    private Boolean githubEnabled;
    private UUID id;
    private UUID teamId;
    private String name;
    private String description;
    private String role;
    private String status;
    private Long memberCount;
    // 项目头像 URL（可为空）。
    private String avatarUrl;
}

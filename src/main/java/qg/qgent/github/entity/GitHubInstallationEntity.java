package qg.qgent.github.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/** 团队已连接的 App 安装，不存储访问 Token。 */
@Data
@TableName("github_installations")
public class GitHubInstallationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long teamId;
    private Long providerInstallationId;
    private String accountLogin;
    private String accountType;
    private Boolean suspended;
}

package qg.qgent.github.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/** 一次性安装回调凭据，绑定发起人、团队和 Web/Mobile 返回入口。 */
@Data
@TableName("github_installation_states")
public class GitHubInstallationStateEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID teamId;
    private UUID actorId;
    private byte[] stateHash;
    private String client;
    private LocalDateTime expiresAt;
    private LocalDateTime usedAt;
}

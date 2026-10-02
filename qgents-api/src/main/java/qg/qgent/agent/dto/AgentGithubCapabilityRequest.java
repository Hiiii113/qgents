package qg.qgent.agent.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 开启或关闭 Agent 的 GitHub 能力，开启后使用团队 GitHub App 授权。 */
@Data
public class AgentGithubCapabilityRequest {
    @NotNull
    private Boolean enabled;
}

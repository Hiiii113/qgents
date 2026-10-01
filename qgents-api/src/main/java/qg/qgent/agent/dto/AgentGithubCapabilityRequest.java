package qg.qgent.agent.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 空引用表示禁用；引用对应部署配置 app.agent.github.<ref>.token。 */
@Data
public class AgentGithubCapabilityRequest {
    @NotNull
    @Size(max = 64)
    @Pattern(regexp = "[A-Za-z0-9_-]*")
    private String credentialRef;
}

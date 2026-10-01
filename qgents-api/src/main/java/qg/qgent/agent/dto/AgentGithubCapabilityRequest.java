package qg.qgent.agent.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 空引用禁用；github-app 使用项目导入仓库的 App 授权；其他引用读取部署配置。 */
@Data
public class AgentGithubCapabilityRequest {
    @NotNull
    @Size(max = 64)
    @Pattern(regexp = "[A-Za-z0-9_-]*")
    private String credentialRef;
}

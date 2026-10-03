package qg.qgent.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 创建自定义 Agent 的名称、用途、提示词与可选 GitHub 能力。 */
@Data
@Schema(description = "创建自定义 Agent 请求")
public class AgentCreateRequest {
    @Schema(description = "是否开启 GitHub 能力，使用团队 GitHub App 授权")
    private boolean githubEnabled;

    @NotBlank(message = "name 不能为空白")
    @Size(max = 255, message = "name 最长 255 个字符")
    @Schema(description = "Agent 昵称，去除首尾空白后非空", example = "Java 后端 Agent")
    private String name;

    @Size(max = 2048, message = "avatar 最长 2048 个字符")
    @Schema(description = "合法头像 URL，可为 null", example = "https://cdn.example.com/avatars/java.png")
    private String avatar;

    @NotBlank(message = "role 不能为空白")
    @Size(max = 32, message = "role 最长 32 个字符")
    @Schema(description = "Agent 用途标签，仅用于展示，不决定执行阶段或工具权限", example = "CODING")
    private String role;

    @Size(max = 5000, message = "description 最长 5000 个字符")
    @Schema(description = "Agent 用途描述（展示与选用决策依据），可为 null")
    private String description;

    @NotBlank(message = "prompt 不能为空白")
    @Size(max = 20000, message = "prompt 最长 20000 个字符")
    @Schema(description = "Agent 系统提示词；不得包含凭据、Token、私钥或宿主机敏感信息")
    private String prompt;
}

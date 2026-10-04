package qg.qgent.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 编辑 Agent；所有字段可选，默认 Agent 仅允许团队所有者修改 name 与 avatar。 */
@Data
@Schema(description = "编辑 Agent 请求；默认 Agent 仅支持昵称与头像")
public class AgentUpdateRequest {
    @Schema(description = "是否开启 GitHub 能力，使用团队 GitHub App 授权")
    private Boolean githubEnabled;

    @Size(max = 255, message = "name 最长 255 个字符")
    @Schema(description = "Agent 昵称，去除首尾空白后非空（可选）")
    private String name;

    @Size(max = 2048, message = "avatar 最长 2048 个字符")
    @Schema(description = "合法头像 URL，传空串清空（可选）")
    private String avatar;

    @Size(max = 32, message = "role 最长 32 个字符")
    @Schema(description = "用途标签（可选），不决定执行阶段或工具权限")
    private String role;

    @Size(max = 5000, message = "description 最长 5000 个字符")
    @Schema(description = "Agent 用途描述（可选）")
    private String description;

    @Size(max = 20000, message = "prompt 最长 20000 个字符")
    @Schema(description = "Agent 系统提示词（可选）；不得包含凭据、Token、私钥或宿主机敏感信息")
    private String prompt;
}

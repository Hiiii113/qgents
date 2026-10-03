package qg.qgent.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 拒绝 Agent 发布的请求，原因可选。 */
@Data
public class AgentReviewRejectRequest {
    // 拒绝原因仅创建者可见。
    @Size(max = 1000)
    @Schema(description = "拒绝原因（可选，最长 1000 字符）", maxLength = 1000)
    private String reason;
}

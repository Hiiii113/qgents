package qg.qgent.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 发起私聊请求：对端类型与 ID；同一项目的同一对端重复发起返回同一会话。
 */
@Data
public class DirectConversationCreateRequest {

    @NotBlank
    @Schema(description = "对端类型：USER / AGENT", requiredMode = Schema.RequiredMode.REQUIRED)
    private String peerType;

    @NotNull
    @Schema(description = "对端 ID：项目成员用户 ID 或本项目已启用的 Agent ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long peerId;
}

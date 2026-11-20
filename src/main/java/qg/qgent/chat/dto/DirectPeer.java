package qg.qgent.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 私聊对端：另一位用户或一个 Agent。
 */
public record DirectPeer(
    @Schema(description = "对端类型：USER / AGENT") String type,
    @Schema(description = "对端 ID") String id,
    @Schema(description = "对端显示名称") String displayName,
    @Schema(description = "对端头像 URL；没有头像为空") String avatarUrl) {
}

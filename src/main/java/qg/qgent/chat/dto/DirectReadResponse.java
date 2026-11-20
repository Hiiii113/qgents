package qg.qgent.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 私聊已读响应：推进后的已读游标与清零的未读数。
 */
public record DirectReadResponse(
    @Schema(description = "私聊会话 ID") String conversationId,
    @Schema(description = "推进后的已读游标（= 会话最新消息 sequence）") Long lastReadSequenceNo,
    @Schema(description = "未读数，恒为 0（推进后）") Long unreadCount) {
}

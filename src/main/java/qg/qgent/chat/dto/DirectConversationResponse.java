package qg.qgent.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 私聊会话视图：对端、最新消息摘要和当前用户的未读数。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DirectConversationResponse {

    @Schema(description = "会话 ID；消息与实时事件里的 groupId 就是它")
    private String id;

    @Schema(description = "所属项目 ID")
    private String projectId;

    @Schema(description = "对端用户或 Agent")
    private DirectPeer peer;

    @Schema(description = "最新消息摘要；从未发言为空")
    private GroupLatestMessage lastMessage;

    @Schema(description = "最近消息时间（ISO8601 UTC）；从未发言为空")
    private String lastMessageAt;

    @Schema(description = "最近活跃时间（ISO8601 UTC），会话列表按它倒序")
    private String latestActivityAt;

    @Schema(description = "创建时间（ISO8601 UTC）")
    private String createdAt;

    @Schema(description = "当前用户在该会话的未读消息数（排除本人消息），≥ 0")
    private Long unreadCount;
}

package qg.qgent.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 私聊会话的参与者：两位，按类型（USER 在前）与 ID 升序规范化存储。
 * <p>
 * 主键就是会话所在的 chat_groups 行；同一项目同一对端由唯一键保证只有一条会话。
 */
@Data
@TableName("direct_conversations")
public class DirectConversationEntity {
    public static final String TYPE_USER = "USER";
    public static final String TYPE_AGENT = "AGENT";

    @TableId(value = "group_id", type = IdType.INPUT)
    private Long groupId;

    private Long projectId;
    private String participantAType;
    private Long participantAId;
    private String participantBType;
    private Long participantBId;
    private LocalDateTime createdAt;

    /**
     * 该会话里对端是 Agent 时返回它的 ID；人与人会话返回 null。
     * 参与者 A 固定是用户，因此 Agent 只可能是 B。
     */
    public Long agentPeerId() {
        return TYPE_AGENT.equals(participantBType) ? participantBId : null;
    }

    /**
     * 当前用户是否属于这个会话。
     */
    public boolean involves(Long userId) {
        return isUser(participantAType, participantAId, userId)
            || isUser(participantBType, participantBId, userId);
    }

    /**
     * 当前用户在会话里的对端：类型与 ID。
     */
    public Peer peerOf(Long userId) {
        return isUser(participantAType, participantAId, userId)
            ? new Peer(participantBType, participantBId)
            : new Peer(participantAType, participantAId);
    }

    private static boolean isUser(String type, Long id, Long userId) {
        return TYPE_USER.equals(type) && id.equals(userId);
    }

    public record Peer(String type, Long id) {
    }
}

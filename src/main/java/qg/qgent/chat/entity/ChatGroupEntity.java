package qg.qgent.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目群聊与私聊会话；私聊只是另一种会话，participant 关系在 direct_conversations。
 * <p>
 * 默认群共享项目成员；新建群仅包含受邀用户和 Agent。
 */
@Data
@TableName("chat_groups")
public class ChatGroupEntity {
    public static final String KIND_GROUP = "GROUP";
    public static final String KIND_DIRECT = "DIRECT";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private Long createdBy;

    private Boolean defaultGroup = true;
    private String kind = KIND_GROUP;
    private String name;
    private LocalDateTime lastMessageAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 私聊会话与群聊共用 chat_groups/messages 行，群专属能力（成员邀请、群记忆、跨群来源）据此排除。
     */
    public boolean isDirect() {
        return KIND_DIRECT.equals(kind);
    }
}

package qg.qgent.chat.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;

/** 默认群共享项目成员；新建群仅包含受邀用户和 Agent。 */
@Data
@TableName("chat_groups")
public class ChatGroupEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private Long createdBy;

    private Boolean defaultGroup = true;
    private String name;
    private LocalDateTime lastMessageAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

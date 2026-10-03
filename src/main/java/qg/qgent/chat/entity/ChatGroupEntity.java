package qg.qgent.chat.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;
/** 默认群共享项目成员；新建群仅包含受邀用户和 Agent。 */
@Data
@TableName("chat_groups")
public class ChatGroupEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID projectId;
    private UUID createdBy;
    private Boolean defaultGroup = true;
    private String name;
    private String description;
    private LocalDateTime lastMessageAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

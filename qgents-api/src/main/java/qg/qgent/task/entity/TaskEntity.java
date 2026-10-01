package qg.qgent.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@TableName(value = "tasks", autoResultMap = true)
public class TaskEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID projectId;
    private UUID requirementGroupId;
    private UUID triggerMessageId;
    private UUID workspaceId;
    private String title;
    private String displayCode;
    private String requirement;
    private String status;
    private UUID createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

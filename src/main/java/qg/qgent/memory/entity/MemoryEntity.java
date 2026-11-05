package qg.qgent.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 项目确认知识实体，对应表 memories。 */
@Data
@TableName(value = "memories", autoResultMap = true)
public class MemoryEntity {

    // Memory ID。
    @TableId(type = IdType.AUTO)
    private Long id;

    // 所属项目 ID。
    private Long projectId;

    // 创建用户 ID。
    private Long createdBy;

    // 知识标题。
    private String title;

    // 经确认的项目事实正文（MEDIUMTEXT）。
    private String content;

    // 知识分类标识，如 ENGINEERING_DECISION。
    private String category;

    // 标签 JSON 数组。
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    // 状态枚举：DRAFT / PENDING_REVIEW / APPROVED / REJECTED / ARCHIVED。
    private String status;

    // 最近提交审核用户 ID；从未提交审核时为空。
    private Long submittedBy;

    // 最近提交审核时间（UTC）；从未提交审核时为空。
    private LocalDateTime submittedAt;

    // 最近审核用户 ID；可为空。
    private Long reviewerId;

    // 最近驳回原因；可为空。
    private String rejectionReason;

    // 最近审核时间（UTC）；可为空。
    private LocalDateTime reviewedAt;

    // 创建时间（UTC）。
    private LocalDateTime createdAt;

    // 更新时间（UTC）。
    private LocalDateTime updatedAt;
}

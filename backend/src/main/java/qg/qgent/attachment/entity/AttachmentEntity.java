package qg.qgent.attachment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/** attachments 表的上传记录；消息通过 content 中的 attachmentId 引用附件。 */
@Data
@TableName("attachments")
public class AttachmentEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;

    private UUID projectId;

    private UUID uploadedBy;

    private String objectKey;

    private String fileName;

    private String mediaType;

    private Long sizeBytes;

    private String status;

    private LocalDateTime createdAt;
}

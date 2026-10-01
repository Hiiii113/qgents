package qg.qgent.mergerequest.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@TableName("merge_requests")
public class MergeRequestEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID projectRepositoryId;
    private UUID taskId;
    private UUID workspaceId;
    private String provider;
    private Long providerNumber;
    private String sourceBranch;
    private String targetBranch;
    private String headCommit;
    private Boolean mergeable;
    private String mergeableState;
    private String baseSha;
    private String title;
    private String htmlUrl;
    private String status;
    private LocalDateTime providerUpdatedAt;
    private LocalDateTime syncedAt;
    private UUID authorUserId;
    private LocalDateTime createdAt;
}

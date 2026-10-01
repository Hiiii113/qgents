package qg.qgent.task.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request to create one task from a project chat conversation.
 */
@Data
public class TaskCreateRequest {
    @NotNull
    @Schema(description = "项目群聊 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID groupId;

    @Schema(description = "Optional triggering message identifier")
    private UUID triggerMessageId;

    @NotBlank
    @Size(max = 255)
    @Schema(description = "Task title", maxLength = 255, requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @NotBlank
    @Size(max = 10000)
    @Schema(description = "Requirement snapshot", maxLength = 10000, requiredMode = Schema.RequiredMode.REQUIRED)
    private String requirement;

    @Schema(description = "执行该任务的 Agent；不传则使用通用 Agent")
    private UUID agentId;

}

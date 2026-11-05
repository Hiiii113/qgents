package qg.qgent.workspace.dto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
@Data
public class WorkspaceProvisionRequest {
    @NotNull
    @Schema(type = "string")
    private Long projectId;
}

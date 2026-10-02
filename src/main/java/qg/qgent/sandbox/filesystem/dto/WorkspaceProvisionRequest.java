package qg.qgent.sandbox.filesystem.dto;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.UUID;
@Data
public class WorkspaceProvisionRequest {
    @NotNull
    private UUID projectId;
}

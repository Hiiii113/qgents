package qg.qgent.workspace.dto;
import lombok.*;
import java.util.UUID;
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceResponse {
    private UUID id;
    private UUID projectId;
    private String storageKey;
    private String status;
    private String createdAt;
    private String updatedAt;
}

package qg.qgent.workspace.dto;
import lombok.*;
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceResponse {
    private Long id;
    private Long projectId;
    private String storageKey;
    private String status;
    private String createdAt;
    private String updatedAt;
}

package qg.qgent.project.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class ProjectMemberAddRequest {
    @NotNull
    private UUID userId;
}

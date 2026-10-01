package qg.qgent.project.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ProjectMemberUpdateRequest {
    @NotBlank
    private String role;
}

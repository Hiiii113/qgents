package qg.qgent.team.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TeamMemberUpdateRequest {
    @NotBlank
    private String role;
}

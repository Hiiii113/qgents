package qg.qgent.user.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import qg.qgent.project.dto.ProjectResponse;
import qg.qgent.team.dto.TeamResponse;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CurrentUserResponse {
    private UserResponse user;
    private List<TeamResponse> teams;
    private List<ProjectResponse> projects;
    private String currentTeamId;
}

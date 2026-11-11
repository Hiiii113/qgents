package qg.qgent.team.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TeamInvitationResponse {
    private String id;
    private String email;
    private String status;
    private LocalDateTime expiresAt;
}

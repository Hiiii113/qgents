package qg.qgent.user.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CurrentTeamRequest(@NotNull UUID teamId) {}

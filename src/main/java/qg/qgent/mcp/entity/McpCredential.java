package qg.qgent.mcp.entity;

import java.time.Instant;

public record McpCredential(Long id, Long ownerUserId, String accessToken, String refreshToken,
                            Instant expiresAt, boolean reauthRequired, long revision) {
    public boolean expired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}

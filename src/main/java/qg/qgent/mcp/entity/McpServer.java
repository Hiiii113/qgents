package qg.qgent.mcp.entity;

public record McpServer(Long id, Long projectId, String name, String endpoint, Scope scope, AuthMode authMode,
                        boolean enabled, String oauthClient) {
    public enum Scope { SHARED, PERSONAL }

    public enum AuthMode { BEARER, OAUTH }

    public Long credentialOwner(Long actor) {
        return scope == Scope.PERSONAL ? actor : null;
    }
}

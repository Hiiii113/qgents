package qg.qgent.mcp.dto;

import qg.qgent.mcp.entity.McpServer.AuthMode;
import qg.qgent.mcp.entity.McpServer.Scope;

import java.util.List;

public record McpConnectionView(String id, String name, String url, Scope scope, AuthMode authMode,
                                boolean enabled, CredentialStatus credentialStatus, List<String> agentIds) {
    public enum CredentialStatus { MISSING, CONNECTED, REAUTH_REQUIRED }
}

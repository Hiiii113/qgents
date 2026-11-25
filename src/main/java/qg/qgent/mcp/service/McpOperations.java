package qg.qgent.mcp.service;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.common.web.ApiException;
import qg.qgent.mcp.entity.McpCredential;
import qg.qgent.mcp.entity.McpServer;
import qg.qgent.mcp.service.McpConnections.Authorized;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class McpOperations {
    private final McpConnections connections;
    private final McpCredentials credentials;
    private final McpRemote remote;

    public List<String> test(Long projectId, Long actor, long serverId) {
        Supplier<Authorized> authorize = () -> connections.authorizeTest(projectId, actor, serverId);
        return execute(authorize.get(), authorize, remote::list).stream().map(McpSchema.Tool::name).toList();
    }

    public List<McpServer> assigned(RunActor actor) {
        return connections.assigned(actor.projectId(), actor.agentId(), actor.userId());
    }

    public Catalog discover(RunActor actor, long serverId) {
        Supplier<Authorized> authorize = authorization(actor, serverId);
        Authorized connection = authorize.get();
        return new Catalog(version(connection.credential()), execute(connection, authorize, remote::list));
    }

    public McpSchema.CallToolResult call(RunActor actor, long serverId, CredentialVersion version,
                                       String tool, Map<String, Object> arguments) {
        Supplier<Authorized> authorize = authorization(actor, serverId);
        Authorized connection = authorize.get();
        requireSame(version, connection);
        return execute(connection, authorize, (endpoint, token) -> remote.call(endpoint, token, tool, arguments));
    }

    private Supplier<Authorized> authorization(RunActor actor, long serverId) {
        return () -> connections.authorize(actor.projectId(), actor.agentId(), actor.userId(), serverId);
    }

    private <T> T execute(Authorized connection, Supplier<Authorized> authorize, BiFunction<URI, String, T> action) {
        URI endpoint = URI.create(connection.server().endpoint());
        McpCredential credential = connection.credential();
        CredentialVersion version = version(credential);
        String token = credentials.currentToken(credential);
        if (credentials.needsRefresh(credential)) {
            token = credentials.refresh(connection.server(), credential, null);
            requireSame(version, authorize.get());
        }
        try {
            return action.apply(endpoint, token);
        } catch (ApiException e) {
            if (!McpRemote.UNAUTHORIZED.equals(e.code())) throw e;
        }
        Authorized current = authorize.get();
        requireSame(version, current);
        String renewed = credentials.refresh(current.server(), current.credential(), token);
        requireSame(version, authorize.get());
        try {
            return action.apply(endpoint, renewed);
        } catch (ApiException e) {
            if (!McpRemote.UNAUTHORIZED.equals(e.code())) throw e;
            credentials.rejectIfCurrent(current.credential(), renewed);
            throw McpCredentials.reauthorize();
        }
    }

    private void requireSame(CredentialVersion expected, Authorized connection) {
        if (!expected.equals(version(connection.credential()))) {
            throw new ApiException(HttpStatus.CONFLICT, "MCP_CREDENTIAL_CHANGED", "MCP 凭证已变更，请重新发起此次操作");
        }
    }

    private CredentialVersion version(McpCredential credential) {
        return new CredentialVersion(credential.id(), credential.revision());
    }

    public record RunActor(Long projectId, Long agentId, Long userId) {}
    public record CredentialVersion(long id, long revision) {}
    public record Catalog(CredentialVersion version, List<McpSchema.Tool> tools) {}
}

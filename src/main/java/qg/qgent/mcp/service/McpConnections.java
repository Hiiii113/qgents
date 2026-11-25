package qg.qgent.mcp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.agent.service.AgentService;
import qg.qgent.common.web.ApiException;
import qg.qgent.mcp.dto.McpConnectionView;
import qg.qgent.mcp.dto.McpConnectionView.CredentialStatus;
import qg.qgent.mcp.entity.McpCredential;
import qg.qgent.mcp.entity.McpServer;
import qg.qgent.mcp.entity.McpServer.AuthMode;
import qg.qgent.mcp.entity.McpServer.Scope;
import qg.qgent.mcp.mapper.McpMapper;
import qg.qgent.project.service.ProjectAccessService;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class McpConnections {
    private final McpMapper mapper;
    private final ProjectAccessService access;
    private final AgentService agents;
    private final McpCredentials credentials;

    public List<McpConnectionView> list(Long projectId, Long actor) {
        access.requireProjectMember(projectId, actor);
        return mapper.list(projectId).stream().map(server -> view(server, actor)).toList();
    }

    @Transactional
    public McpConnectionView create(Long projectId, Long actor, String name, String url, Scope scope, AuthMode authMode) {
        access.requireProjectAdmin(projectId, actor);
        String endpoint = McpEndpoint.parse(url).toASCIIString();
        long id = mapper.create(projectId, name.strip(), endpoint, scope, authMode);
        return view(requireServer(projectId, id, false), actor);
    }

    @Transactional
    public void update(Long projectId, Long actor, Long serverId, String name, boolean enabled) {
        access.requireProjectAdmin(projectId, actor);
        mapper.update(requireServer(projectId, serverId, true).id(), name.strip(), enabled);
    }

    @Transactional
    public void delete(Long projectId, Long actor, Long serverId) {
        access.requireProjectAdmin(projectId, actor);
        mapper.delete(requireServer(projectId, serverId, true).id());
    }

    @Transactional
    public void saveBearer(Long projectId, Long actor, Long serverId, String token) {
        McpServer server = requireEditable(projectId, actor, serverId, true);
        requireMode(server, AuthMode.BEARER);
        credentials.save(ownerCredential(server, actor), new McpOAuthClient.Tokens(token.strip(), null, null), true);
    }

    @Transactional
    public void completeOAuth(Long projectId, Long actor, long serverId, long revision, McpOAuthClient.Tokens tokens) {
        McpServer server = requireEnabled(requireEditable(projectId, actor, serverId, true));
        McpCredential credential = ownerCredential(server, actor);
        if (credential.revision() != revision) {
            throw new ApiException(HttpStatus.CONFLICT, "MCP_CREDENTIAL_CHANGED", "MCP 凭证已变更，请重新授权");
        }
        credentials.save(credential, tokens, true);
    }

    @Transactional
    public void disconnect(Long projectId, Long actor, Long serverId) {
        McpServer server = requireEditable(projectId, actor, serverId, true);
        mapper.credential(serverId, server.credentialOwner(actor), true)
            .ifPresent(credential -> mapper.saveCredential(credential.id(), null, null, null, true));
    }

    @Transactional
    public void assign(Long projectId, Long actor, Long serverId, List<Long> agentIds) {
        McpServer server = requireEditable(projectId, actor, serverId, true);
        List<Long> distinct = agentIds.stream().distinct().toList();
        distinct.forEach(id -> agents.requireActiveProjectAgent(projectId, id));
        mapper.replaceAgents(ownerCredential(server, actor).id(), distinct);
    }

    McpServer requireEditable(Long projectId, Long actor, long serverId, boolean lock) {
        String role = access.requireProjectMember(projectId, actor);
        McpServer server = requireServer(projectId, serverId, lock);
        if (server.scope() == Scope.SHARED && !"PROJECT_ADMIN".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限");
        }
        return server;
    }

    McpCredential ownerCredential(McpServer server, Long actor) {
        return mapper.ensureCredential(server.id(), server.credentialOwner(actor));
    }

    static void requireMode(McpServer server, AuthMode mode) {
        if (server.authMode() != mode) {
            throw new ApiException(HttpStatus.CONFLICT, "MCP_AUTH_MODE_MISMATCH", "此 MCP 使用另一种鉴权方式");
        }
    }

    McpServer requireEnabled(McpServer server) {
        if (!server.enabled()) throw new ApiException(HttpStatus.CONFLICT, "MCP_DISABLED", "此 MCP 连接已停用");
        return server;
    }

    List<McpServer> assigned(Long projectId, Long agentId, Long actor) {
        access.requireProjectMember(projectId, actor);
        return mapper.assignedServers(projectId, agentId, actor);
    }

    Authorized authorize(Long projectId, Long agentId, Long actor, long serverId) {
        access.requireProjectMember(projectId, actor);
        agents.requireActiveProjectAgent(projectId, agentId);
        McpServer server = requireEnabled(requireServer(projectId, serverId, false));
        McpCredential credential = mapper.assignedCredential(serverId, server.credentialOwner(actor), agentId)
            .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "MCP_NOT_ASSIGNED", "当前 Agent 未获准使用发起人的这条 MCP 连接"));
        return new Authorized(server, credential);
    }

    Authorized authorizeTest(Long projectId, Long actor, long serverId) {
        McpServer server = requireEnabled(requireEditable(projectId, actor, serverId, false));
        McpCredential credential = mapper.credential(serverId, server.credentialOwner(actor), false)
            .orElseThrow(McpCredentials::missing);
        return new Authorized(server, credential);
    }

    private McpServer requireServer(Long projectId, long serverId, boolean lock) {
        return mapper.server(projectId, serverId, lock)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MCP_NOT_FOUND", "MCP 连接不存在"));
    }

    private McpConnectionView view(McpServer server, Long actor) {
        var credential = mapper.credential(server.id(), server.credentialOwner(actor), false);
        CredentialStatus status = credential.map(this::status).orElse(CredentialStatus.MISSING);
        List<String> agentIds = credential.map(value -> mapper.agentIds(value.id()).stream().map(String::valueOf).toList())
            .orElseGet(List::of);
        return new McpConnectionView(server.id().toString(), server.name(), server.endpoint(), server.scope(),
            server.authMode(), server.enabled(), status, agentIds);
    }

    private CredentialStatus status(McpCredential credential) {
        if (credential.reauthRequired() || (credential.expired(Instant.now()) && credential.refreshToken() == null)) {
            return CredentialStatus.REAUTH_REQUIRED;
        }
        return credential.accessToken() == null ? CredentialStatus.MISSING : CredentialStatus.CONNECTED;
    }

    record Authorized(McpServer server, McpCredential credential) {}
}

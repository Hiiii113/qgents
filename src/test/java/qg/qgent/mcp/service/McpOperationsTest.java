package qg.qgent.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.agent.service.AgentService;
import qg.qgent.common.web.ApiException;
import qg.qgent.mcp.entity.McpCredential;
import qg.qgent.mcp.entity.McpServer;
import qg.qgent.mcp.entity.McpServer.AuthMode;
import qg.qgent.mcp.entity.McpServer.Scope;
import qg.qgent.mcp.mapper.McpMapper;
import qg.qgent.mcp.service.McpOperations.RunActor;
import qg.qgent.project.service.ProjectAccessService;

import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class McpOperationsTest {
    private static final Long PROJECT = 12L;
    private static final Long AGENT = 34L;
    private static final Long SERVICE = 56L;
    private static final Long ALICE = 100L;
    private static final Long BOB = 101L;
    private static final URI ENDPOINT = URI.create("https://docs.example.com/mcp");
    private final McpMapper mapper = mock(McpMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final AgentService agents = mock(AgentService.class);
    private final McpRemote remote = mock(McpRemote.class);
    private final McpTokenCipher cipher = new McpTokenCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final McpCredentials credentials = new McpCredentials(mapper, cipher, mock(McpOAuthClient.class), new ObjectMapper());
    private final McpConnections connections = new McpConnections(mapper, access, agents, credentials);
    private final McpOperations operations = new McpOperations(connections, credentials, remote);

    @Test
    void sameAgentUsesEachInitiatorsTokenAndRejectsAnothersDiscoveredTool() {
        setServer(Scope.PERSONAL, true);
        setCredential(ALICE, 1L, "alice-token");
        setCredential(BOB, 2L, "bob-token");
        RunActor alice = new RunActor(PROJECT, AGENT, ALICE);
        RunActor bob = new RunActor(PROJECT, AGENT, BOB);
        var aliceTool = operations.discover(alice, SERVICE).version();
        var bobTool = operations.discover(bob, SERVICE).version();
        operations.call(alice, SERVICE, aliceTool, "docs.read", Map.of());
        operations.call(bob, SERVICE, bobTool, "docs.read", Map.of());
        assertThatThrownBy(() -> operations.call(bob, SERVICE, aliceTool, "docs.read", Map.of()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("MCP_CREDENTIAL_CHANGED"));
        verify(remote).list(ENDPOINT, "alice-token");
        verify(remote).list(ENDPOINT, "bob-token");
        verify(remote).call(ENDPOINT, "alice-token", "docs.read", Map.of());
        verify(remote).call(ENDPOINT, "bob-token", "docs.read", Map.of());
        verifyNoMoreInteractions(remote);
    }

    @Test
    void revocationAfterDiscoveryPreventsRemoteCall() {
        setServer(Scope.PERSONAL, true);
        setCredential(ALICE, 1L, "alice-token");
        RunActor alice = new RunActor(PROJECT, AGENT, ALICE);
        var version = operations.discover(alice, SERVICE).version();
        when(mapper.assignedCredential(SERVICE, ALICE, AGENT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> operations.call(alice, SERVICE, version, "docs.read", Map.of()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("MCP_NOT_ASSIGNED"));
        verify(remote).list(ENDPOINT, "alice-token");
        verifyNoMoreInteractions(remote);
    }

    @Test
    void sharedTokenIsUsableThroughAssignedAgentButEditableOnlyByAdmin() {
        setServer(Scope.SHARED, true);
        setCredential(null, 3L, "shared-token");
        when(access.requireProjectMember(PROJECT, BOB)).thenReturn("PROJECT_MEMBER");
        RunActor bob = new RunActor(PROJECT, AGENT, BOB);
        var version = operations.discover(bob, SERVICE).version();
        operations.call(bob, SERVICE, version, "docs.search", Map.of());
        assertThatThrownBy(() -> connections.saveBearer(PROJECT, BOB, SERVICE, "replaced"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status())
                .isEqualTo(HttpStatus.FORBIDDEN));
        verify(remote).call(ENDPOINT, "shared-token", "docs.search", Map.of());
    }

    private void setServer(Scope scope, boolean enabled) {
        var server = new McpServer(SERVICE, PROJECT, "Docs", ENDPOINT.toString(), scope, AuthMode.BEARER, enabled, null);
        when(mapper.server(PROJECT, SERVICE, false)).thenReturn(Optional.of(server));
        when(mapper.server(PROJECT, SERVICE, true)).thenReturn(Optional.of(server));
    }

    private void setCredential(Long owner, long id, String token) {
        var credential = new McpCredential(id, owner, cipher.encrypt(token, id), null, null, false, 1);
        when(mapper.assignedCredential(SERVICE, owner, AGENT)).thenReturn(Optional.of(credential));
        when(remote.list(ENDPOINT, token)).thenReturn(List.of(McpSchema.Tool.builder("docs.read", Map.of("type", "object")).build()));
    }
}

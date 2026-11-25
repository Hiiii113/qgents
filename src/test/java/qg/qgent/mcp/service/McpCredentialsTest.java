package qg.qgent.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.common.web.ApiException;
import qg.qgent.mcp.entity.McpCredential;
import qg.qgent.mcp.entity.McpServer;
import qg.qgent.mcp.entity.McpServer.AuthMode;
import qg.qgent.mcp.entity.McpServer.Scope;
import qg.qgent.mcp.mapper.McpMapper;
import qg.qgent.mcp.service.McpOAuthClient.Client;
import qg.qgent.mcp.service.McpOAuthClient.Tokens;

import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class McpCredentialsTest {
    private static final long ID = 123L;
    private static final URI ENDPOINT = URI.create("https://docs.example.com/mcp");
    private final McpMapper mapper = mock(McpMapper.class);
    private final McpOAuthClient oauth = mock(McpOAuthClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final McpTokenCipher cipher = new McpTokenCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final McpCredentials credentials = new McpCredentials(mapper, cipher, oauth, json);
    private final Client client = new Client(ENDPOINT.resolve("/authorize"), ENDPOINT.resolve("/token"), ENDPOINT, null, "qgents");

    @Test
    void storesBothRotatedTokensEncryptedAndUsesAlreadyRenewedTokenForConcurrentRequest() throws Exception {
        McpServer server = server();
        var expired = new McpCredential(ID, 100L, cipher.encrypt("old-access", ID), cipher.encrypt("old-refresh", ID),
            Instant.now().minusSeconds(30), false, 1);
        when(mapper.lockCredential(ID)).thenReturn(Optional.of(expired));
        var expires = Instant.now().plusSeconds(3600);
        when(oauth.refresh(client, "old-refresh")).thenReturn(new Tokens("new-access", "new-refresh", expires));
        assertThat(credentials.refresh(server, expired, null)).isEqualTo("new-access");

        var tokens = ArgumentCaptor.forClass(String.class);
        verify(mapper).saveCredential(eq(ID), tokens.capture(), tokens.capture(), eq(expires), eq(false));
        String access = tokens.getAllValues().get(0);
        String refresh = tokens.getAllValues().get(1);
        assertThat(access).isNotEqualTo("new-access");
        assertThat(refresh).isNotEqualTo("new-refresh");
        assertThat(cipher.decrypt(access, ID)).isEqualTo("new-access");
        assertThat(cipher.decrypt(refresh, ID)).isEqualTo("new-refresh");

        when(mapper.lockCredential(ID)).thenReturn(Optional.of(new McpCredential(ID, 100L, access, refresh, expires, false, 1)));
        assertThat(credentials.refresh(server, expired, "old-access")).isEqualTo("new-access");
        verify(oauth).refresh(client, "old-refresh");
        verifyNoMoreInteractions(oauth);
    }

    @Test
    void invalidGrantMarksOnlyThatCredentialForReauthorization() throws Exception {
        var expired = new McpCredential(ID, 100L, cipher.encrypt("access", ID), cipher.encrypt("refresh", ID),
            Instant.EPOCH, false, 2);
        when(mapper.lockCredential(ID)).thenReturn(Optional.of(expired));
        when(oauth.refresh(client, "refresh")).thenThrow(McpCredentials.reauthorize());
        assertThatThrownBy(() -> credentials.refresh(server(), expired, null))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("MCP_REAUTH_REQUIRED"));
        verify(mapper).requireReauthorization(ID);
    }

    private McpServer server() throws Exception {
        return new McpServer(42L, 2L, "Docs", ENDPOINT.toString(), Scope.PERSONAL, AuthMode.OAUTH, true,
            json.writeValueAsString(client));
    }
}

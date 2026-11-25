package qg.qgent.mcp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.common.web.ApiException;
import qg.qgent.mcp.entity.McpCredential;
import qg.qgent.mcp.entity.McpServer;
import qg.qgent.mcp.mapper.McpMapper;
import qg.qgent.mcp.service.McpOAuthClient.Client;
import qg.qgent.mcp.service.McpOAuthClient.Tokens;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class McpCredentials {
    private final McpMapper mapper;
    private final McpTokenCipher cipher;
    private final McpOAuthClient oauth;
    private final ObjectMapper json;

    void requireConfigured() {
        cipher.requireConfigured();
    }

    void save(McpCredential credential, Tokens tokens, boolean replace) {
        long id = credential.id();
        mapper.saveCredential(id, cipher.encrypt(tokens.accessToken(), id), cipher.encrypt(tokens.refreshToken(), id),
            tokens.expiresAt(), replace);
    }

    String currentToken(McpCredential credential) {
        if (credential.reauthRequired()) throw reauthorize();
        if (credential.accessToken() == null) throw missing();
        return cipher.decrypt(credential.accessToken(), credential.id());
    }

    boolean needsRefresh(McpCredential credential) {
        Instant now = Instant.now();
        return credential.expired(credential.refreshToken() == null ? now : now.plusSeconds(30));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public String refresh(McpServer server, McpCredential snapshot, String rejectedToken) {
        McpCredential current = lock(snapshot);
        String accessToken = currentToken(current);
        if (!needsRefresh(current) && (rejectedToken == null || !rejectedToken.equals(accessToken))) return accessToken;
        if (current.refreshToken() == null) {
            mapper.requireReauthorization(current.id());
            throw reauthorize();
        }
        String refreshToken = cipher.decrypt(current.refreshToken(), current.id());
        try {
            Tokens fresh = oauth.refresh(client(server), refreshToken);
            save(current, fresh, false);
            return fresh.accessToken();
        } catch (ApiException e) {
            if ("MCP_REAUTH_REQUIRED".equals(e.code())) mapper.requireReauthorization(current.id());
            throw e;
        }
    }

    @Transactional
    public void rejectIfCurrent(McpCredential snapshot, String rejectedToken) {
        McpCredential current = lock(snapshot);
        if (rejectedToken.equals(currentToken(current))) mapper.requireReauthorization(current.id());
    }

    private McpCredential lock(McpCredential snapshot) {
        return mapper.lockCredential(snapshot.id()).filter(value -> value.revision() == snapshot.revision())
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "MCP_CREDENTIAL_CHANGED", "MCP 凭证已变更，请重试"));
    }

    Client client(McpServer server) {
        try {
            return json.readValue(server.oauthClient(), Client.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid persisted MCP OAuth client", e);
        }
    }

    static ApiException missing() {
        return new ApiException(HttpStatus.CONFLICT, "MCP_CREDENTIAL_MISSING", "请在项目设置的 MCP 页签连接账号或填写凭证");
    }

    static ApiException reauthorize() {
        return new ApiException(HttpStatus.CONFLICT, "MCP_REAUTH_REQUIRED", "MCP 凭证已失效，请在项目设置中重新连接或更新凭证");
    }
}

package qg.qgent.mcp.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;
import qg.qgent.common.web.ApiException;
import qg.qgent.mcp.entity.McpServer.AuthMode;
import qg.qgent.mcp.mapper.McpMapper;
import qg.qgent.mcp.service.McpOAuthClient.Tokens;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

@Service
public class McpOAuthFlow {
    public static final String CALLBACK = "/api/v1/integrations/mcp/callback";
    public static final String CLIENT_METADATA = "/api/v1/integrations/mcp/client-metadata.json";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private final McpConnections connections;
    private final McpCredentials credentials;
    private final McpMapper mapper;
    private final McpOAuthClient oauth;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final String publicBase;
    private final String frontendBase;
    private final SecureRandom random = new SecureRandom();

    public McpOAuthFlow(McpConnections connections, McpCredentials credentials, McpMapper mapper,
                         McpOAuthClient oauth, StringRedisTemplate redis, ObjectMapper json,
                         @Value("${app.mcp.public-base-url}") String publicBase,
                         @Value("${app.frontend-url-web}") String frontendBase) {
        this.connections = connections;
        this.credentials = credentials;
        this.mapper = mapper;
        this.oauth = oauth;
        this.redis = redis;
        this.json = json;
        this.publicBase = publicBase;
        this.frontendBase = frontendBase;
    }

    @Transactional
    public URI start(Long projectId, Long actor, Long serverId) {
        var server = connections.requireEnabled(connections.requireEditable(projectId, actor, serverId, true));
        McpConnections.requireMode(server, AuthMode.OAUTH);
        credentials.requireConfigured();
        URI redirectUri = publicUri(CALLBACK);
        var client = server.oauthClient() == null
            ? oauth.discover(URI.create(server.endpoint()), redirectUri, publicUri(CLIENT_METADATA))
            : credentials.client(server);
        if (server.oauthClient() == null) mapper.saveOAuthClient(server.id(), encode(client));
        var credential = connections.ownerCredential(server, actor);
        String state = randomValue();
        String verifier = randomValue();
        Start attempt = new Start(projectId, serverId, actor, credential.revision(), verifier, redirectUri);
        URI authorizationUrl = oauth.authorizationUrl(client, redirectUri, state, verifier);
        redis.opsForValue().set(key(state), encode(attempt), STATE_TTL);
        return authorizationUrl;
    }

    public URI frontendCallback(String state, String code) {
        Start attempt = read(state);
        String fragment = "mcpState=" + state;
        if (code != null && code.length() <= 4096) fragment += "&mcpCode=" + URLEncoder.encode(code, StandardCharsets.UTF_8);
        return URI.create(UriComponentsBuilder.fromUriString(frontendBase)
            .pathSegment("app", "projects", attempt.projectId().toString(), "settings")
            .queryParam("tab", "mcp").build().toUriString() + "#" + fragment);
    }

    public void complete(Long projectId, Long actor, String state, String code) {
        Start attempt = read(state);
        if (!projectId.equals(attempt.projectId()) || !actor.equals(attempt.actor())) throw invalidState();
        var server = connections.requireEnabled(connections.requireEditable(projectId, actor, attempt.serverId(), false));
        if (!Boolean.TRUE.equals(redis.delete(key(state)))) throw invalidState();
        if (code == null || code.isBlank()) return;
        Tokens tokens = oauth.exchange(credentials.client(server), attempt.redirectUri(), code, attempt.verifier());
        connections.completeOAuth(projectId, actor, server.id(), attempt.revision(), tokens);
    }

    public ClientMetadata clientMetadata() {
        return new ClientMetadata(publicUri(CLIENT_METADATA), "Qgents", List.of(publicUri(CALLBACK)),
            List.of("authorization_code", "refresh_token"), List.of("code"), "none");
    }

    private Start read(String state) {
        if (state == null || !state.matches("[A-Za-z0-9_-]{43}")) throw invalidState();
        String data = redis.opsForValue().get(key(state));
        if (data == null) throw invalidState();
        try {
            return json.readValue(data, Start.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid persisted MCP OAuth state", e);
        }
    }

    private URI publicUri(String path) {
        if (publicBase.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MCP_OAUTH_NOT_CONFIGURED", "服务端尚未配置 MCP OAuth 回调地址");
        }
        URI base = McpEndpoint.parse(publicBase);
        if (base.getRawQuery() != null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MCP_OAUTH_NOT_CONFIGURED", "MCP OAuth 回调基地址不能包含查询参数");
        }
        return UriComponentsBuilder.fromUri(base).path(path).build(true).toUri();
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode MCP OAuth state", e);
        }
    }

    private String randomValue() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String key(String state) {
        return "mcp:oauth:" + state;
    }

    private static ApiException invalidState() {
        return new ApiException(HttpStatus.BAD_REQUEST, "MCP_OAUTH_STATE_INVALID", "MCP 授权已过期或不属于当前账号，请重新连接");
    }

    record Start(Long projectId, Long serverId, Long actor, long revision, String verifier, URI redirectUri) {}

    public record ClientMetadata(
        @JsonProperty("client_id") URI clientId,
        @JsonProperty("client_name") String clientName,
        @JsonProperty("redirect_uris") List<URI> redirectUris,
        @JsonProperty("grant_types") List<String> grantTypes,
        @JsonProperty("response_types") List<String> responseTypes,
        @JsonProperty("token_endpoint_auth_method") String tokenEndpointAuthMethod) {}
}

package qg.qgent.mcp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import qg.qgent.common.web.ApiException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class McpOAuthClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_RESPONSE = 1024 * 1024;
    private static final String INITIALIZE = """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
        "capabilities":{},"clientInfo":{"name":"Qgents","version":"1"}}}
        """;
    private static final Pattern AUTH_PART = Pattern.compile(
        "(?:^|,)\\s*([A-Za-z_][A-Za-z0-9_-]*)\\s*(?:=\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|([^,\\s]+))|\\s+)");

    private final Predicate<URI> allowedEndpoint;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public McpOAuthClient(ObjectMapper json) {
        this(McpEndpoint::isPublicHttps, httpClient(), json, Clock.systemUTC());
    }

    McpOAuthClient(Predicate<URI> allowedEndpoint) {
        this(allowedEndpoint, httpClient(), new ObjectMapper(), Clock.systemUTC());
    }

    McpOAuthClient(Predicate<URI> allowedEndpoint, HttpClient http, ObjectMapper json, Clock clock) {
        this.allowedEndpoint = allowedEndpoint;
        this.http = http;
        this.json = json;
        this.clock = clock;
    }

    public Client discover(URI server, URI redirectUri, URI clientMetadataUri) {
        if (clientMetadataUri != null && (!"https".equalsIgnoreCase(clientMetadataUri.getScheme())
            || clientMetadataUri.getPath() == null || clientMetadataUri.getPath().length() <= 1)) {
            throw unsupported("客户端元数据文档需要包含路径的 HTTPS 地址");
        }
        Map<String, String> challenge = challenge(probe(server));
        JsonNode protectedResource = resourceMetadata(server, challenge.get("resource_metadata"));
        URI resource = providerUri(required(protectedResource, "resource"));
        if (!resourceKey(resource).equals(resourceKey(server))
            || (resource.getRawQuery() != null && !resource.getRawQuery().equals(server.getRawQuery()))) {
            throw remote("OAuth 受保护资源与 MCP 服务地址不一致");
        }
        JsonNode authorization = authorizationMetadata(strings(protectedResource, "authorization_servers"));
        URI authorize = providerUri(required(authorization, "authorization_endpoint"));
        URI token = providerUri(required(authorization, "token_endpoint"));
        String scope = challenge.get("scope");
        if (scope == null) scope = String.join(" ", strings(protectedResource, "scopes_supported"));
        if (scope.isBlank()) scope = null;
        String clientId = clientMetadataUri != null && authorization.path("client_id_metadata_document_supported").asBoolean()
            ? clientMetadataUri.toASCIIString() : register(authorization, redirectUri, scope);
        return new Client(authorize, token, resource, scope, clientId);
    }

    public URI authorizationUrl(Client client, URI redirectUri, String state, String verifier) {
        var query = new LinkedHashMap<>(Map.of(
            "response_type", "code", "client_id", client.clientId(), "redirect_uri", redirectUri.toASCIIString(),
            "state", state, "code_challenge", challenge(verifier), "code_challenge_method", "S256",
            "resource", client.resource().toASCIIString()));
        if (client.scope() != null) query.put("scope", client.scope());
        String separator = client.authorizationEndpoint().getRawQuery() == null ? "?" : "&";
        return URI.create(client.authorizationEndpoint().toASCIIString() + separator + form(query));
    }

    public Tokens exchange(Client client, URI redirectUri, String code, String verifier) {
        return tokens(client, Map.of("grant_type", "authorization_code", "code", code,
            "redirect_uri", redirectUri.toASCIIString(), "code_verifier", verifier), null);
    }

    public Tokens refresh(Client client, String refreshToken) {
        return tokens(client, Map.of("grant_type", "refresh_token", "refresh_token", refreshToken), refreshToken);
    }

    private HttpHeaders probe(URI server) {
        var request = request(server).header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(INITIALIZE)).build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                int status = response.statusCode();
                if (status == 429 || status >= 500 || (status >= 300 && status < 400)) throw responseFailure(status);
                return status == 401 ? response.headers() : HttpHeaders.of(Map.of(), (name, value) -> true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw remote("OAuth 服务请求被中断");
        } catch (IOException e) {
            throw remote("无法连接 MCP 服务进行 OAuth 发现");
        }
    }

    private JsonNode resourceMetadata(URI server, String challengeMetadata) {
        var candidates = new LinkedHashSet<URI>();
        if (challengeMetadata != null) candidates.add(providerUri(challengeMetadata));
        String wellKnown = origin(server) + "/.well-known/oauth-protected-resource";
        if (!server.getRawPath().isEmpty() && !server.getRawPath().equals("/")) {
            candidates.add(URI.create(wellKnown + server.getRawPath()));
        }
        candidates.add(URI.create(wellKnown));
        for (URI uri : candidates) {
            JsonNode metadata = metadata(uri);
            if (metadata != null) return metadata;
        }
        throw unsupported("该服务未公开 OAuth 受保护资源元数据");
    }

    private JsonNode authorizationMetadata(List<String> issuers) {
        for (String value : issuers) {
            URI issuer = providerUri(value);
            if (issuer.getRawQuery() != null) throw remote("OAuth 授权服务器地址无效");
            String base = origin(issuer);
            String path = issuer.getRawPath().replaceFirst("/+$", "");
            List<URI> candidates = new ArrayList<>(List.of(
                URI.create(base + "/.well-known/oauth-authorization-server" + path),
                URI.create(base + "/.well-known/openid-configuration" + path)));
            if (!path.isEmpty()) candidates.add(URI.create(base + path + "/.well-known/openid-configuration"));
            for (URI uri : candidates) {
                JsonNode metadata = metadata(uri);
                if (metadata == null) continue;
                if (!required(metadata, "issuer").equals(value)) throw remote("OAuth 授权服务器的 issuer 不匹配");
                requireSupport(metadata, "token_endpoint_auth_methods_supported", "none");
                requireSupport(metadata, "response_types_supported", "code");
                requireSupport(metadata, "grant_types_supported", "authorization_code");
                requireSupport(metadata, "code_challenge_methods_supported", "S256");
                return metadata;
            }
        }
        throw unsupported("该服务未公开 OAuth 授权服务器元数据");
    }

    private JsonNode metadata(URI uri) {
        var response = send(request(uri).header("Accept", "application/json").GET().build());
        if (response.statusCode() == 404 || response.statusCode() == 410) return null;
        requireSuccess(response.statusCode());
        return object(response.body());
    }

    private String register(JsonNode authorization, URI redirectUri, String scope) {
        String registration = optional(authorization, "registration_endpoint");
        if (registration == null) throw unsupported("该服务需要预注册 OAuth 客户端");
        var payload = new LinkedHashMap<String, Object>(Map.of(
            "client_name", "Qgents", "application_type", "web", "redirect_uris", List.of(redirectUri.toASCIIString()),
            "token_endpoint_auth_method", "none", "grant_types", List.of("authorization_code", "refresh_token"),
            "response_types", List.of("code")));
        if (scope != null) payload.put("scope", scope);
        var response = post(providerUri(registration), "application/json", encode(payload));
        int status = response.statusCode();
        if (status >= 400 && status < 500 && status != 429) throw unsupported("授权服务器拒绝自动注册 OAuth 客户端");
        requireSuccess(status);
        JsonNode client = object(response.body());
        String method = optional(client, "token_endpoint_auth_method");
        if (method != null && !method.equals("none")) throw unsupported("OAuth 服务需要客户端密钥");
        if (client.has("redirect_uris") && !strings(client, "redirect_uris").contains(redirectUri.toASCIIString())) {
            throw remote("OAuth 注册返回的回调地址不匹配");
        }
        return required(client, "client_id");
    }

    private Tokens tokens(Client client, Map<String, String> fields, String previousRefreshToken) {
        var body = new LinkedHashMap<>(fields);
        body.put("client_id", client.clientId());
        body.put("resource", client.resource().toASCIIString());
        var response = post(client.tokenEndpoint(), "application/x-www-form-urlencoded", form(body));
        int status = response.statusCode();
        if ((status == 400 || status == 401) && "invalid_grant".equals(optional(object(response.body()), "error"))) {
            throw McpCredentials.reauthorize();
        }
        requireSuccess(status);
        JsonNode token = object(response.body());
        String access = required(token, "access_token");
        String tokenType = optional(token, "token_type");
        if ((tokenType != null && !tokenType.equalsIgnoreCase("Bearer")) || !access.matches("[A-Za-z0-9._~+/-]+=*")) {
            throw remote("OAuth 服务返回了无效的 Bearer Token");
        }
        String refresh = optional(token, "refresh_token");
        return new Tokens(access, refresh == null ? previousRefreshToken : refresh, expiry(token.get("expires_in")));
    }

    private Instant expiry(JsonNode expiresIn) {
        if (expiresIn == null || expiresIn.isNull()) return null;
        try {
            long seconds = Long.parseLong(expiresIn.asText());
            if (seconds < 0) throw new NumberFormatException();
            return clock.instant().plusSeconds(seconds);
        } catch (DateTimeException | ArithmeticException | NumberFormatException e) {
            throw remote("OAuth 服务返回了无效的 expires_in");
        }
    }

    private HttpResponse<byte[]> post(URI uri, String contentType, String body) {
        return send(request(uri).header("Accept", "application/json").header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        var pending = http.sendAsync(request, info -> new LimitedBody());
        try {
            return pending.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw remote("OAuth 服务请求被中断");
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw remote("OAuth 服务响应超时");
        } catch (ExecutionException e) {
            throw remote("无法读取 OAuth 服务响应");
        }
    }

    private HttpRequest.Builder request(URI uri) {
        if (!allowedEndpoint.test(uri)) throw remote("OAuth 端点必须是公网 HTTPS 地址");
        return HttpRequest.newBuilder(uri).timeout(TIMEOUT);
    }

    private static HttpClient httpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private JsonNode object(byte[] contents) {
        try {
            JsonNode node = json.readTree(contents);
            if (node != null && node.isObject()) return node;
        } catch (IOException e) {
            throw remote("OAuth 服务响应格式错误");
        }
        throw remote("OAuth 服务响应格式错误");
    }

    private String encode(Map<String, Object> fields) {
        try {
            return json.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode OAuth client metadata", e);
        }
    }

    private static Map<String, String> challenge(HttpHeaders headers) {
        var parameters = new LinkedHashMap<String, String>();
        for (String header : headers.allValues("WWW-Authenticate")) {
            boolean bearer = false;
            var parts = AUTH_PART.matcher(header);
            while (parts.find()) {
                String name = parts.group(1).toLowerCase(Locale.ROOT);
                String quoted = parts.group(2);
                String unquoted = parts.group(3);
                if (quoted == null && unquoted == null) {
                    bearer = name.equals("bearer");
                    parts.region(parts.end(), header.length());
                    if (bearer) parseFirstParameter(parts, header, parameters);
                } else if (bearer) {
                    parameters.putIfAbsent(name, quoted == null ? unquoted : quoted.replaceAll("\\\\(.)", "$1"));
                }
            }
        }
        return parameters;
    }

    private static void parseFirstParameter(java.util.regex.Matcher parts, String header, Map<String, String> parameters) {
        int start = parts.regionStart();
        if (parts.lookingAt() && (parts.group(2) != null || parts.group(3) != null)) {
            String quoted = parts.group(2);
            parameters.putIfAbsent(parts.group(1).toLowerCase(Locale.ROOT),
                quoted == null ? parts.group(3) : quoted.replaceAll("\\\\(.)", "$1"));
            parts.region(parts.end(), header.length());
        } else {
            parts.region(start, header.length());
        }
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream()
            .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    }

    private static String required(JsonNode metadata, String key) {
        String value = optional(metadata, key);
        if (value == null) throw remote("OAuth 服务响应缺少 " + key);
        return value;
    }

    private static String optional(JsonNode metadata, String key) {
        JsonNode value = metadata.get(key);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw remote("OAuth 服务响应的 " + key + " 格式错误");
        return value.asText().isBlank() ? null : value.asText();
    }

    private static List<String> strings(JsonNode metadata, String key) {
        JsonNode array = metadata.get(key);
        if (array == null) return List.of();
        if (!array.isArray()) throw remote("OAuth 服务响应的 " + key + " 格式错误");
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            if (!value.isTextual() || value.asText().isBlank()) throw remote("OAuth 服务响应的 " + key + " 格式错误");
            values.add(value.asText());
        }
        return values;
    }

    private static void requireSupport(JsonNode metadata, String key, String required) {
        if (metadata.has(key) && !strings(metadata, key).contains(required)) {
            throw unsupported("该服务不支持公共客户端的授权码与 PKCE 流程");
        }
    }

    private URI providerUri(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null && allowedEndpoint.test(uri)) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid endpoint metadata is reported without returning the supplied URL.
        }
        throw remote("OAuth 元数据包含无效的服务地址");
    }

    private static String origin(URI uri) {
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    private static URI resourceKey(URI uri) {
        int port = uri.getPort();
        if (("https".equalsIgnoreCase(uri.getScheme()) && port == 443) || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80)) port = -1;
        String path = uri.getRawPath().equals("/") ? "" : uri.getRawPath();
        return URI.create(uri.getScheme() + "://" + uri.getHost() + (port < 0 ? "" : ":" + port) + path);
    }

    private static void requireSuccess(int status) {
        if (status < 200 || status >= 300) throw responseFailure(status);
    }

    private static ApiException responseFailure(int status) {
        return remote("OAuth 服务请求失败（HTTP " + status + "）");
    }

    private static ApiException remote(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "MCP_OAUTH_REMOTE", message);
    }

    private static ApiException unsupported(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MCP_OAUTH_UNSUPPORTED", message);
    }

    public record Client(URI authorizationEndpoint, URI tokenEndpoint, URI resource, String scope, String clientId) {}
    public record Tokens(String accessToken, String refreshToken, Instant expiresAt) {}

    private static final class LimitedBody implements BodySubscriber<byte[]> {
        private final BodySubscriber<byte[]> body = BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long bytes;

        @Override
        public CompletionStage<byte[]> getBody() { return body.getBody(); }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            body.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            bytes += buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (bytes > MAX_RESPONSE) {
                subscription.cancel();
                body.onError(new IOException("OAuth response exceeds size limit"));
            } else {
                body.onNext(buffers);
            }
        }

        @Override
        public void onError(Throwable error) { body.onError(error); }

        @Override
        public void onComplete() { body.onComplete(); }
    }
}

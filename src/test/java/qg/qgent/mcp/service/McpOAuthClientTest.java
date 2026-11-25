package qg.qgent.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.common.web.ApiException;

class McpOAuthClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final URI REDIRECT = URI.create("http://127.0.0.1:3000/oauth/callback");
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

    private LocalServer server;
    private McpOAuthClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = new LocalServer();
        client = new McpOAuthClient(endpoint -> true);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void discoversFromChallengeAndRegistersPublicClient() {
        server.on(
                "POST",
                "/mcp",
                request -> {
                    assertThat(request.body()).contains("\"initialize\"");
                    assertThat(request.header("Authorization")).isNull();
                    return response(
                            401,
                            "",
                            "WWW-Authenticate",
                            "Bearer resource_metadata=\""
                                    + server.uri(
                                            "/.well-known/oauth-protected-resource/mcp")
                                    + "\", scope=\"mcp:read mcp:write\"");
                });
        server.on(
                "GET",
                "/.well-known/oauth-protected-resource/mcp",
                request ->
                        response(
                                200,
                                """
                                {"resource":"%s","authorization_servers":["%s"],"scopes_supported":["ignored"]}
                                """
                                        .formatted(server.uri("/mcp"), server.uri("/tenant1")),
                                "Content-Type",
                                "application/json"));
        server.on(
                "GET",
                "/.well-known/oauth-authorization-server/tenant1",
                request ->
                        response(
                                200,
                                authorizationServerMetadata(
                                        server.uri("/tenant1"),
                                        server.uri("/authorize?existing=1"),
                                        server.uri("/token"),
                                        server.uri("/register"),
                                        false),
                                "Content-Type",
                                "application/json"));
        server.on(
                "POST",
                "/register",
                request ->
                        response(
                                201,
                                """
                                {"client_id":"client-abc","redirect_uris":["%s"],"token_endpoint_auth_method":"none"}
                                """
                                        .formatted(REDIRECT),
                                "Content-Type",
                                "application/json"));

        var discovered = client.discover(server.uri("/mcp"), REDIRECT, null);

        assertThat(discovered.authorizationEndpoint())
                .isEqualTo(server.uri("/authorize?existing=1"));
        assertThat(discovered.tokenEndpoint()).isEqualTo(server.uri("/token"));
        assertThat(discovered.resource()).isEqualTo(server.uri("/mcp"));
        assertThat(discovered.scope()).isEqualTo("mcp:read mcp:write");
        assertThat(discovered.clientId()).isEqualTo("client-abc");

        assertThat(server.requests())
                .extracting(Request::method)
                .containsExactly("POST", "GET", "GET", "POST");
        assertThat(server.requests())
                .allSatisfy(request -> assertThat(request.header("Authorization")).isNull());

        Request registration = server.requests().get(3);
        JsonNode registrationBody = json(registration.body());
        assertThat(registrationBody.path("redirect_uris").get(0).asText())
                .isEqualTo(REDIRECT.toString());
        assertThat(registrationBody.path("token_endpoint_auth_method").asText())
                .isEqualTo("none");
        assertThat(registrationBody.path("application_type").asText()).isEqualTo("web");
        assertThat(registrationBody.path("grant_types").toString())
                .contains("authorization_code", "refresh_token");
        assertThat(registrationBody.path("scope").asText())
                .isEqualTo("mcp:read mcp:write");
        assertThat(registrationBody.has("client_secret")).isFalse();
    }

    @Test
    void fallsBackToRootProtectedResourceAndUsesConfiguredCimdBeforeDcr() {
        server.on("POST", "/mcp", request -> response(401, ""));
        server.on(
                "GET",
                "/.well-known/oauth-protected-resource",
                request ->
                        response(
                                200,
                                """
                                {"resource":"%s","authorization_servers":["%s"],"scopes_supported":["mcp:read","mcp:write"]}
                                """
                                        .formatted(server.uri("/mcp"), server.uri("/")),
                                "Content-Type",
                                "application/json"));
        server.on(
                "GET",
                "/.well-known/oauth-authorization-server",
                request ->
                        response(
                                200,
                                authorizationServerMetadata(
                                        server.uri("/"),
                                        server.uri("/authorize"),
                                        server.uri("/token"),
                                        server.uri("/register"),
                                        true),
                                "Content-Type",
                                "application/json"));
        URI clientMetadata = URI.create("https://qgents.example/oauth/client.json");

        var discovered = client.discover(server.uri("/mcp"), REDIRECT, clientMetadata);

        assertThat(discovered.clientId()).isEqualTo(clientMetadata.toString());
        assertThat(discovered.scope()).isEqualTo("mcp:read mcp:write");
        assertThat(server.requests())
                .extracting(Request::path)
                .doesNotContain("/register");
        assertThat(server.requests())
                .extracting(Request::path)
                .containsExactly(
                        "/mcp",
                        "/.well-known/oauth-protected-resource/mcp",
                        "/.well-known/oauth-protected-resource",
                        "/.well-known/oauth-authorization-server");
    }

    @Test
    void fallsBackToAppendedOpenIdConfigurationForIssuerWithPath() {
        server.on("POST", "/mcp", request -> response(401, ""));
        server.on(
                "GET",
                "/.well-known/oauth-protected-resource/mcp",
                request ->
                        response(
                                200,
                                """
                                {"resource":"%s","authorization_servers":["%s"]}
                                """
                                        .formatted(server.uri("/mcp"), server.uri("/tenant1")),
                                "Content-Type",
                                "application/json"));
        server.on(
                "GET",
                "/tenant1/.well-known/openid-configuration",
                request ->
                        response(
                                200,
                                authorizationServerMetadata(
                                        server.uri("/tenant1"),
                                        server.uri("/authorize"),
                                        server.uri("/token"),
                                        server.uri("/register"),
                                        false),
                                "Content-Type",
                                "application/json"));
        server.on(
                "POST",
                "/register",
                request ->
                        response(
                                201,
                                """
                                {"client_id":"client-oidc","redirect_uris":["%s"]}
                                """
                                        .formatted(REDIRECT),
                                "Content-Type",
                                "application/json"));

        var discovered = client.discover(server.uri("/mcp"), REDIRECT, null);

        assertThat(discovered.clientId()).isEqualTo("client-oidc");
        assertThat(server.requests())
                .extracting(Request::path)
                .containsExactly(
                        "/mcp",
                        "/.well-known/oauth-protected-resource/mcp",
                        "/.well-known/oauth-authorization-server/tenant1",
                        "/.well-known/openid-configuration/tenant1",
                        "/tenant1/.well-known/openid-configuration",
                        "/register");
    }

    @Test
    void reportsUnsupportedWhenNoAutomaticRegistrationIsAdvertised() {
        server.on("POST", "/mcp", request -> response(401, ""));
        server.on(
                "GET",
                "/.well-known/oauth-protected-resource/mcp",
                request ->
                        response(
                                200,
                                """
                                {"resource":"%s","authorization_servers":["%s"]}
                                """
                                        .formatted(server.uri("/mcp"), server.uri("/")),
                                "Content-Type",
                                "application/json"));
        server.on(
                "GET",
                "/.well-known/oauth-authorization-server",
                request ->
                        response(
                                200,
                                """
                                {"issuer":"%s","authorization_endpoint":"%s","token_endpoint":"%s","token_endpoint_auth_methods_supported":["none"],"code_challenge_methods_supported":["S256"]}
                                """
                                        .formatted(
                                                server.uri("/"),
                                                server.uri("/authorize"),
                                                server.uri("/token")),
                                "Content-Type",
                                "application/json"));

        assertThatThrownBy(() -> client.discover(server.uri("/mcp"), REDIRECT, null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.status())
                                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                            assertThat(error.code()).isEqualTo("MCP_OAUTH_UNSUPPORTED");
                        });
    }

    @Test
    void reportsUnsupportedWhenProtectedResourceMetadataIsMissing() {
        server.on("POST", "/mcp", request -> response(401, ""));

        assertThatThrownBy(() -> client.discover(server.uri("/mcp"), REDIRECT, null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.status())
                                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                            assertThat(error.code()).isEqualTo("MCP_OAUTH_UNSUPPORTED");
                        });
        assertThat(server.requests())
                .extracting(Request::path)
                .containsExactly(
                        "/mcp",
                        "/.well-known/oauth-protected-resource/mcp",
                        "/.well-known/oauth-protected-resource");
    }

    @Test
    void doesNotFollowMetadataRedirects() {
        server.on(
                "POST",
                "/mcp",
                request ->
                        response(
                                401,
                                "",
                                "WWW-Authenticate",
                                "Bearer resource_metadata=\"" + server.uri("/prm") + "\""));
        server.on(
                "GET",
                "/prm",
                request ->
                        response(
                                302,
                                "",
                                "Location",
                                server.uri("/evil-prm").toString()));
        server.on(
                "GET",
                "/evil-prm",
                request ->
                        response(
                                200,
                                """
                                {"resource":"%s","authorization_servers":["%s"]}
                                """
                                        .formatted(server.uri("/mcp"), server.uri("/")),
                                "Content-Type",
                                "application/json"));

        assertThatThrownBy(() -> client.discover(server.uri("/mcp"), REDIRECT, null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                            assertThat(error.code()).isEqualTo("MCP_OAUTH_REMOTE");
                        });
        assertThat(server.requests())
                .extracting(Request::path)
                .doesNotContain("/evil-prm");
    }

    @Test
    void reportsProviderOutageAsRemoteFailure() {
        server.on("POST", "/mcp", request -> response(401, ""));
        server.on(
                "GET",
                "/.well-known/oauth-protected-resource",
                request -> response(503, "unavailable"));

        assertThatThrownBy(() -> client.discover(server.uri("/mcp"), REDIRECT, null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                            assertThat(error.code()).isEqualTo("MCP_OAUTH_REMOTE");
                        });
    }

    @Test
    void rejectsMetadataLargerThanResponseBudget() {
        server.on("POST", "/mcp", request -> response(401, ""));
        server.on(
                "GET",
                "/.well-known/oauth-protected-resource",
                request ->
                        response(
                                200,
                                "{\"padding\":\"" + "x".repeat(1024 * 1024) + "\"}",
                                "Content-Type",
                                "application/json"));

        assertThatThrownBy(() -> client.discover(server.uri("/mcp"), REDIRECT, null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("MCP_OAUTH_REMOTE"));
    }

    @Test
    void rejectsResourceMetadataThatDoesNotMatchTheMcpServer() {
        server.on("POST", "/mcp", request -> response(401, ""));
        server.on(
                "GET",
                "/.well-known/oauth-protected-resource/mcp",
                request ->
                        response(
                                200,
                                """
                                {"resource":"%s","authorization_servers":["%s"]}
                                """
                                        .formatted(server.uri("/other"), server.uri("/")),
                                "Content-Type",
                                "application/json"));

        assertThatThrownBy(() -> client.discover(server.uri("/mcp"), REDIRECT, null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("MCP_OAUTH_REMOTE"));
    }

    @Test
    void rejectsAuthorizationServerMetadataWithMismatchedIssuer() {
        server.on("POST", "/mcp", request -> response(401, ""));
        server.on(
                "GET",
                "/.well-known/oauth-protected-resource/mcp",
                request ->
                        response(
                                200,
                                """
                                {"resource":"%s","authorization_servers":["%s"]}
                                """
                                        .formatted(server.uri("/mcp"), server.uri("/as")),
                                "Content-Type",
                                "application/json"));
        server.on(
                "GET",
                "/.well-known/oauth-authorization-server/as",
                request ->
                        response(
                                200,
                                authorizationServerMetadata(
                                        server.uri("/other"),
                                        server.uri("/authorize"),
                                        server.uri("/token"),
                                        server.uri("/register"),
                                        false),
                                "Content-Type",
                                "application/json"));

        assertThatThrownBy(() -> client.discover(server.uri("/mcp"), REDIRECT, null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("MCP_OAUTH_REMOTE"));
    }

    @Test
    void reportsUnsupportedWhenDynamicRegistrationIsRejected() {
        server.on("POST", "/mcp", request -> response(401, ""));
        server.on(
                "GET",
                "/.well-known/oauth-protected-resource/mcp",
                request ->
                        response(
                                200,
                                """
                                {"resource":"%s","authorization_servers":["%s"]}
                                """
                                        .formatted(server.uri("/mcp"), server.uri("/")),
                                "Content-Type",
                                "application/json"));
        server.on(
                "GET",
                "/.well-known/oauth-authorization-server",
                request ->
                        response(
                                200,
                                authorizationServerMetadata(
                                        server.uri("/"),
                                        server.uri("/authorize"),
                                        server.uri("/token"),
                                        server.uri("/register"),
                                        false),
                                "Content-Type",
                                "application/json"));
        server.on("POST", "/register", request -> response(400, "{}"));

        assertThatThrownBy(() -> client.discover(server.uri("/mcp"), REDIRECT, null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.status())
                                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                            assertThat(error.code()).isEqualTo("MCP_OAUTH_UNSUPPORTED");
                        });
    }

    @Test
    void authorizationUrlCarriesRfc8707ResourceAndS256Challenge() {
        var client =
                new McpOAuthClient.Client(
                        URI.create("https://as.example/authorize?tenant=1"),
                        URI.create("https://as.example/token"),
                        URI.create("https://mcp.example.com/mcp"),
                        "mcp:read mcp:write",
                        "client-abc");

        URI url =
                this.client.authorizationUrl(
                        client, REDIRECT, "state-1", VERIFIER);

        assertThat(url.getScheme()).isEqualTo("https");
        assertThat(url.getHost()).isEqualTo("as.example");
        assertThat(url.getPath()).isEqualTo("/authorize");
        Map<String, String> query = form(url.getRawQuery());
        assertThat(query)
                .containsEntry("tenant", "1")
                .containsEntry("response_type", "code")
                .containsEntry("client_id", "client-abc")
                .containsEntry("redirect_uri", REDIRECT.toString())
                .containsEntry("state", "state-1")
                .containsEntry(
                        "code_challenge",
                        "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("scope", "mcp:read mcp:write")
                .containsEntry("resource", "https://mcp.example.com/mcp");
    }

    @Test
    void exchangeSendsFormEncodedCodeAndResource() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        server.on(
                "POST",
                "/token",
                request ->
                        response(
                                200,
                                """
                                {"access_token":"access","token_type":"Bearer","expires_in":3600,"refresh_token":"refresh"}
                                """,
                                "Content-Type",
                                "application/json"));
        var client = clientWithFixedClock(now);
        var discovered =
                new McpOAuthClient.Client(
                        URI.create("https://as.example/authorize"),
                        server.uri("/token?region=eu"),
                        URI.create("https://mcp.example.com/mcp"),
                        "mcp:read",
                        "client-abc");

        var tokens = client.exchange(discovered, REDIRECT, "code-1", VERIFIER);

        assertThat(tokens.accessToken()).isEqualTo("access");
        assertThat(tokens.refreshToken()).isEqualTo("refresh");
        assertThat(tokens.expiresAt()).isEqualTo(now.plusSeconds(3600));

        Request request = server.requests().getFirst();
        assertThat(request.rawQuery()).isEqualTo("region=eu");
        assertThat(request.header("Authorization")).isNull();
        assertThat(request.header("Content-Type"))
                .startsWith("application/x-www-form-urlencoded");
        assertThat(form(request.body()))
                .containsEntry("grant_type", "authorization_code")
                .containsEntry("code", "code-1")
                .containsEntry("redirect_uri", REDIRECT.toString())
                .containsEntry("client_id", "client-abc")
                .containsEntry("code_verifier", VERIFIER)
                .containsEntry("resource", "https://mcp.example.com/mcp");
    }

    @Test
    void exchangeLeavesOptionalTokenFieldsNull() {
        server.on(
                "POST",
                "/token",
                request ->
                        response(
                                200,
                                "{\"access_token\":\"access\",\"token_type\":\"bearer\"}",
                                "Content-Type",
                                "application/json"));
        var discovered =
                new McpOAuthClient.Client(
                        URI.create("https://as.example/authorize"),
                        server.uri("/token"),
                        URI.create("https://mcp.example.com/mcp"),
                        null,
                        "client-abc");

        var tokens = client.exchange(discovered, REDIRECT, "code-1", VERIFIER);

        assertThat(tokens.accessToken()).isEqualTo("access");
        assertThat(tokens.refreshToken()).isNull();
        assertThat(tokens.expiresAt()).isNull();
    }

    @Test
    void refreshKeepsExistingRefreshTokenWhenServerDoesNotRotate() {
        server.on(
                "POST",
                "/token",
                request ->
                        response(
                                200,
                                "{\"access_token\":\"new-access\",\"token_type\":\"Bearer\",\"expires_in\":60}",
                                "Content-Type",
                                "application/json"));
        var discovered =
                new McpOAuthClient.Client(
                        URI.create("https://as.example/authorize"),
                        server.uri("/token"),
                        URI.create("https://mcp.example.com/mcp"),
                        null,
                        "client-abc");

        var tokens = client.refresh(discovered, "old-refresh");

        assertThat(tokens.accessToken()).isEqualTo("new-access");
        assertThat(tokens.refreshToken()).isEqualTo("old-refresh");
        assertThat(tokens.expiresAt()).isNotNull();
        assertThat(form(server.requests().getFirst().body()))
                .containsEntry("grant_type", "refresh_token")
                .containsEntry("refresh_token", "old-refresh")
                .containsEntry("client_id", "client-abc")
                .containsEntry("resource", "https://mcp.example.com/mcp");
    }

    @Test
    void invalidGrantRequiresReauthorization() {
        server.on(
                "POST",
                "/token",
                request ->
                        response(
                                400,
                                "{\"error\":\"invalid_grant\",\"error_description\":\"revoked\"}",
                                "Content-Type",
                                "application/json"));
        var discovered =
                new McpOAuthClient.Client(
                        URI.create("https://as.example/authorize"),
                        server.uri("/token"),
                        URI.create("https://mcp.example.com/mcp"),
                        null,
                        "client-abc");

        assertThatThrownBy(() -> client.refresh(discovered, "old-refresh"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(error.code()).isEqualTo("MCP_REAUTH_REQUIRED");
                        });
    }

    @Test
    void parsesBearerChallengeWithQuotedCommaAndOtherChallenge() {
        server.on(
                "POST",
                "/mcp",
                request ->
                        response(
                                401,
                                "",
                                "WWW-Authenticate",
                                "Basic realm=\"mcp\", Bearer error=\"invalid_token\", "
                                        + "resource_metadata=\""
                                        + server.uri(
                                                "/.well-known/oauth-protected-resource?source=a,b")
                                        + "\", scope=\"one two\""));
        server.on(
                "GET",
                "/.well-known/oauth-protected-resource",
                request ->
                        response(
                                200,
                                """
                                {"resource":"%s","authorization_servers":["%s"],"scopes_supported":["ignored"]}
                                """
                                        .formatted(server.uri("/mcp"), server.uri("/")),
                                "Content-Type",
                                "application/json"));
        server.on(
                "GET",
                "/.well-known/oauth-authorization-server",
                request ->
                        response(
                                200,
                                authorizationServerMetadata(
                                        server.uri("/"),
                                        server.uri("/authorize"),
                                        server.uri("/token"),
                                        server.uri("/register"),
                                        true),
                                "Content-Type",
                                "application/json"));
        URI clientMetadata = URI.create("https://qgents.example/oauth/client.json");

        var discovered = client.discover(server.uri("/mcp"), REDIRECT, clientMetadata);

        assertThat(discovered.scope()).isEqualTo("one two");
        assertThat(discovered.clientId()).isEqualTo(clientMetadata.toString());
        assertThat(server.requests().get(1).rawQuery()).isEqualTo("source=a,b");
    }

    @Test
    void rejectsInvalidClientMetadataUriBeforeNetworkCalls() {
        assertThatThrownBy(
                        () ->
                                client.discover(
                                        server.uri("/mcp"),
                                        REDIRECT,
                                        URI.create("http://qgents.example/oauth/client.json")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.status())
                                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                            assertThat(error.code()).isEqualTo("MCP_OAUTH_UNSUPPORTED");
                        });
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void defaultPolicyAcceptsPublicHttpsAndRejectsLocalOrPrivateAddresses() {
        assertThat(McpEndpoint.isPublicHttps(URI.create("https://8.8.8.8/mcp"))).isTrue();
        assertThat(McpEndpoint.isPublicHttps(URI.create("http://mcp.example.com/mcp"))).isFalse();
        assertThat(McpEndpoint.isPublicHttps(URI.create("https://localhost/mcp"))).isFalse();
        assertThat(McpEndpoint.isPublicHttps(URI.create("https://service.localhost/mcp"))).isFalse();
        assertThat(McpEndpoint.isPublicHttps(URI.create("https://127.0.0.1/mcp"))).isFalse();
        assertThat(McpEndpoint.isPublicHttps(URI.create("https://10.0.0.1/mcp"))).isFalse();
        assertThat(McpEndpoint.isPublicHttps(URI.create("https://169.254.1.1/mcp"))).isFalse();
        assertThat(McpEndpoint.isPublicHttps(URI.create("https://[::1]/mcp"))).isFalse();
        assertThat(McpEndpoint.isPublicHttps(URI.create("https://[fc00::1]/mcp"))).isFalse();
        assertThat(McpEndpoint.isPublicHttps(URI.create("https://user@mcp.example.com/mcp"))).isFalse();
        assertThat(McpEndpoint.isPublicHttps(URI.create("https://mcp.example.com/mcp#fragment"))).isFalse();
    }

    private McpOAuthClient clientWithFixedClock(Instant instant) {
        return new McpOAuthClient(
                endpoint -> true,
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static String authorizationServerMetadata(
            URI issuer, URI authorization, URI token, URI registration, boolean cimd) {
        return """
               {
                 "issuer": "%s",
                 "authorization_endpoint": "%s",
                 "token_endpoint": "%s",
                 "registration_endpoint": "%s",
                 "client_id_metadata_document_supported": %s,
                 "token_endpoint_auth_methods_supported": ["none"],
                 "response_types_supported": ["code"],
                 "grant_types_supported": ["authorization_code", "refresh_token"],
                 "code_challenge_methods_supported": ["S256"],
                 "scopes_supported": ["as:scope"]
               }
               """
                .formatted(issuer, authorization, token, registration, cimd);
    }

    private static JsonNode json(String value) {
        try {
            return JSON.readTree(value);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static Map<String, String> form(String value) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (value == null || value.isBlank())
            return parameters;
        for (String pair : value.split("&")) {
            int separator = pair.indexOf('=');
            String name =
                    URLDecoder.decode(
                            separator < 0 ? pair : pair.substring(0, separator),
                            StandardCharsets.UTF_8);
            String parameterValue =
                    URLDecoder.decode(
                            separator < 0 ? "" : pair.substring(separator + 1),
                            StandardCharsets.UTF_8);
            parameters.put(name, parameterValue);
        }
        return parameters;
    }

    private static Response response(int status, String body, String... headers) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < headers.length; i += 2)
            values.put(headers[i], headers[i + 1]);
        return new Response(status, body, Map.copyOf(values));
    }

    private record Response(int status, String body, Map<String, String> headers) {
    }

    private record Request(
            String method, String path, String rawQuery, Map<String, List<String>> headers,
            String body) {
        String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.getFirst();
        }
    }

    private static final class LocalServer implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, java.util.function.Function<Request, Response>> routes =
                new ConcurrentHashMap<>();
        private final List<Request> requests = new CopyOnWriteArrayList<>();

        LocalServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        URI uri(String pathAndQuery) {
            return URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + pathAndQuery);
        }

        void on(
                String method,
                String path,
                java.util.function.Function<Request, Response> handler) {
            routes.put(method + " " + path, handler);
        }

        List<Request> requests() {
            return List.copyOf(requests);
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                Map<String, List<String>> headers = new LinkedHashMap<>();
                exchange.getRequestHeaders()
                        .forEach(
                                (name, values) ->
                                        headers.put(
                                                name.toLowerCase(Locale.ROOT),
                                                List.copyOf(values)));
                Request request =
                        new Request(
                                exchange.getRequestMethod(),
                                exchange.getRequestURI().getPath(),
                                exchange.getRequestURI().getRawQuery(),
                                headers,
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8));
                requests.add(request);
                var handler =
                        routes.get(
                                exchange.getRequestMethod()
                                        + " "
                                        + exchange.getRequestURI().getPath());
                Response response =
                        handler == null
                                ? response(404, "{\"error\":\"not_found\"}")
                                : handler.apply(request);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                response.headers().forEach(exchange.getResponseHeaders()::set);
                byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
                if (bytes.length == 0) {
                    exchange.sendResponseHeaders(response.status(), -1);
                } else {
                    exchange.sendResponseHeaders(response.status(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}

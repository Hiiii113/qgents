package qg.qgent.mcp.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;
import qg.qgent.mcp.dto.McpConnectionView;
import qg.qgent.mcp.entity.McpServer.AuthMode;
import qg.qgent.mcp.entity.McpServer.Scope;
import qg.qgent.mcp.service.McpConnections;
import qg.qgent.mcp.service.McpOAuthFlow;
import qg.qgent.mcp.service.McpOperations;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/mcp")
@RequiredArgsConstructor
public class ProjectMcpController {
    private final McpConnections connections;
    private final McpOAuthFlow oauth;
    private final McpOperations operations;

    @GetMapping
    public ApiResponse<List<McpConnectionView>> list(@PathVariable Long projectId, @AuthenticationPrincipal Long actor,
                                                      HttpServletRequest request) {
        return ok(connections.list(projectId, actor), request);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<McpConnectionView> create(@PathVariable Long projectId, @AuthenticationPrincipal Long actor,
                                                 @Valid @RequestBody Create body, HttpServletRequest request) {
        return ok(connections.create(projectId, actor, body.name(), body.url(), body.scope(), body.authMode()), request);
    }

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@PathVariable Long projectId, @PathVariable Long id, @AuthenticationPrincipal Long actor,
                       @Valid @RequestBody Update body) {
        connections.update(projectId, actor, id, body.name(), body.enabled());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long projectId, @PathVariable Long id, @AuthenticationPrincipal Long actor) {
        connections.delete(projectId, actor, id);
    }

    @PutMapping("/{id}/credential")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void credential(@PathVariable Long projectId, @PathVariable Long id, @AuthenticationPrincipal Long actor,
                           @Valid @RequestBody Bearer body) {
        connections.saveBearer(projectId, actor, id, body.token());
    }

    @DeleteMapping("/{id}/credential")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable Long projectId, @PathVariable Long id, @AuthenticationPrincipal Long actor) {
        connections.disconnect(projectId, actor, id);
    }

    @PutMapping("/{id}/agents")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assign(@PathVariable Long projectId, @PathVariable Long id, @AuthenticationPrincipal Long actor,
                       @Valid @RequestBody Assign body) {
        connections.assign(projectId, actor, id, body.agentIds());
    }

    @PostMapping("/{id}/oauth")
    public ApiResponse<AuthorizationUrl> begin(@PathVariable Long projectId, @PathVariable Long id,
                                               @AuthenticationPrincipal Long actor, HttpServletRequest request) {
        return ok(new AuthorizationUrl(oauth.start(projectId, actor, id)), request);
    }

    @PostMapping("/oauth/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void finish(@PathVariable Long projectId, @AuthenticationPrincipal Long actor,
                       @Valid @RequestBody Complete body) {
        oauth.complete(projectId, actor, body.state(), body.code());
    }

    @PostMapping("/{id}/test")
    public ApiResponse<TestResult> test(@PathVariable Long projectId, @PathVariable Long id,
                                        @AuthenticationPrincipal Long actor, HttpServletRequest request) {
        return ok(new TestResult(operations.test(projectId, actor, id)), request);
    }

    private static <T> ApiResponse<T> ok(T value, HttpServletRequest request) {
        return ApiResponse.ok(value, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    public record Create(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 2048) String url,
                         @NotNull Scope scope, @NotNull AuthMode authMode) {}
    public record Update(@NotBlank @Size(max = 120) String name, @NotNull Boolean enabled) {}
    public record Assign(@NotNull List<@NotNull @Positive Long> agentIds) {}
    public record Bearer(@NotBlank @Size(max = 16384) @Pattern(regexp = "[A-Za-z0-9._~+/-]+=*") String token) {}
    public record Complete(@NotBlank @Size(max = 43) String state, @Size(max = 4096) String code) {}
    public record AuthorizationUrl(URI authorizationUrl) {}
    public record TestResult(List<String> tools) {}
}

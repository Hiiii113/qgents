package qg.qgent.mcp.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.mcp.service.McpOAuthFlow;
import qg.qgent.mcp.service.McpOAuthFlow.ClientMetadata;

@RestController
@RequiredArgsConstructor
public class McpOAuthController {
    private final McpOAuthFlow oauth;

    @GetMapping(McpOAuthFlow.CALLBACK)
    public ResponseEntity<Void> callback(@RequestParam(required = false) String state,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String error) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
            .location(oauth.frontendCallback(state, error == null ? code : null))
            .cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").build();
    }

    @GetMapping(McpOAuthFlow.CLIENT_METADATA)
    public ClientMetadata clientMetadata() {
        return oauth.clientMetadata();
    }
}

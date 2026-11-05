package qg.qgent.auth.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import qg.qgent.common.web.ApiException;


@Component
public class SecurityContextCurrentActorProvider implements CurrentActorProvider {

    @Override
    public Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required");
        }
        try {
            return Long.valueOf(authentication.getName());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_AUTHENTICATED_SUBJECT",
                    "Authenticated subject must be a decimal user ID");
        }
    }
}

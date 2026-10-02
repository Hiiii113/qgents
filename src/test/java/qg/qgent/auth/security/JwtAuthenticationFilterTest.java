package qg.qgent.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import qg.qgent.auth.service.TokenService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {

    @Test
    void invalidBearerIsRejectedBeforePublicApiHandling() throws Exception {
        TokenService tokens = mock(TokenService.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokens, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        request.addHeader("Authorization", "Bearer invalid");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(tokens).verifyAccess("invalid");
        verifyNoInteractions(chain);
    }
}

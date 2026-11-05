package qg.qgent.auth.security;

import qg.qgent.support.TestIds;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import qg.qgent.auth.service.TokenService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesPreviewQueryToken() throws Exception {
        TokenService tokens = mock(TokenService.class);
        var actor = TestIds.next();
        when(tokens.verifyAccess("preview-token")).thenReturn(actor);
        var request = new MockHttpServletRequest("GET", "/api/v1/projects/p/attachments/a/preview");
        request.setParameter("token", "preview-token");
        var chain = mock(jakarta.servlet.FilterChain.class);
        new JwtAuthenticationFilter(tokens, new ObjectMapper()).doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(actor);
        verify(chain).doFilter(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/projects/p/attachments/a/content", "/api/v1/projects/p/attachments/a/preview-url", "/api/v1/projects"})
    void ignoresQueryTokenOutsidePreview(String path) throws Exception {
        TokenService tokens = mock(TokenService.class);
        var request = new MockHttpServletRequest("GET", path);
        request.setParameter("token", "preview-token");
        new JwtAuthenticationFilter(tokens, new ObjectMapper()).doFilter(request, new MockHttpServletResponse(), mock(jakarta.servlet.FilterChain.class));
        verifyNoInteractions(tokens);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void rejectsInvalidPreviewToken() throws Exception {
        TokenService tokens = mock(TokenService.class);
        when(tokens.verifyAccess("forged")).thenReturn(null);
        var request = new MockHttpServletRequest("GET", "/api/v1/projects/p/attachments/a/preview");
        request.setParameter("token", "forged");
        var response = new MockHttpServletResponse();
        var chain = mock(jakarta.servlet.FilterChain.class);
        new JwtAuthenticationFilter(tokens, new ObjectMapper()).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void bearerTakesPrecedenceOverQueryToken() throws Exception {
        TokenService tokens = mock(TokenService.class);
        var request = new MockHttpServletRequest("GET", "/api/v1/projects/p/attachments/a/preview");
        when(tokens.verifyAccess("invalid")).thenReturn(null);
        request.addHeader("Authorization", "Bearer invalid");
        request.setParameter("token", "valid");
        var response = new MockHttpServletResponse();
        new JwtAuthenticationFilter(tokens, new ObjectMapper()).doFilter(request, response, mock(jakarta.servlet.FilterChain.class));
        assertThat(response.getStatus()).isEqualTo(401);
        verify(tokens).verifyAccess("invalid");
        verifyNoMoreInteractions(tokens);
    }

    @Test
    void invalidBearerIsRejectedBeforePublicApiHandling() throws Exception {
        TokenService tokens = mock(TokenService.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokens, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        when(tokens.verifyAccess("invalid")).thenReturn(null);
        request.addHeader("Authorization", "Bearer invalid");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(tokens).verifyAccess("invalid");
        verifyNoInteractions(chain);
    }
}

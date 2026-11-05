package qg.qgent.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Part;
import org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import qg.qgent.common.entity.IdempotencyRecordEntity;
import qg.qgent.common.service.IdempotencyService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IdempotencyFilterTest {

    @Test
    void multipartRequestsReplayAcrossBoundariesAndRejectChangedParts() throws Exception {
        IdempotencyService service = mock(IdempotencyService.class);
        IdempotencyFilter filter = new IdempotencyFilter(service, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        byte[] file = new byte[1024 * 1024 + 1];
        Arrays.fill(file, (byte) 'a');
        String path = "/api/v1/projects/project/skills";
        MockHttpServletRequest first = multipart(path, "first", file, "private");
        filter.doFilterInternal(first, new MockHttpServletResponse(), (req, res) -> {
            assertArrayEquals(file, ((jakarta.servlet.http.HttpServletRequest) req).getPart("file")
                    .getInputStream().readAllBytes());
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(201);
        });
        ArgumentCaptor<byte[]> hash = ArgumentCaptor.forClass(byte[].class);
        verify(service).save(eq(userId), any(), anyString(), eq("key-123"), hash.capture(),
                eq(201), eq(Map.of()), isNull());
        IdempotencyRecordEntity record = new IdempotencyRecordEntity();
        record.setRequestHash(hash.getValue());
        record.setResponseStatus(201);
        record.setResponseBodyRedacted(Map.of());
        when(service.find(any(), anyString(), eq("key-123"))).thenReturn(record);

        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse replay = new MockHttpServletResponse();
        filter.doFilterInternal(multipart(path, "second", file, "private"), replay, chain);
        assertEquals(201, replay.getStatus());
        verify(chain, never()).doFilter(any(), any());

        byte[] changedFile = file.clone();
        changedFile[changedFile.length - 1] = 'b';
        for (MockHttpServletRequest changed : List.of(
                multipart(path, "third", changedFile, "private"),
                multipart(path, "fourth", file, "shared"))) {
            MockHttpServletResponse conflict = new MockHttpServletResponse();
            filter.doFilterInternal(changed, conflict, chain);
            assertEquals(409, conflict.getStatus());
            assertTrue(conflict.getContentAsString().contains("IDEMPOTENCY_KEY_REUSED"));
        }
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void previewIsNeitherCachedNorRequiredToBeIdempotent() throws Exception {
        IdempotencyService service = mock(IdempotencyService.class);
        IdempotencyFilter filter = new IdempotencyFilter(service, new ObjectMapper());
        MockHttpServletRequest request = multipart("/api/v1/projects/project/skills/preview", "first",
                "---\nname: example\ndescription: 测试用途\n---\n正文\n".getBytes(StandardCharsets.UTF_8),
                "private");
        request.removeHeader("Idempotency-Key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response,
                (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(200));

        assertEquals(200, response.getStatus());
        verifyNoInteractions(service);
    }

    @Test
    void oversizedUploadIsRejectedAtTheParseBoundary() throws Exception {
        IdempotencyService service = mock(IdempotencyService.class);
        IdempotencyFilter filter = new IdempotencyFilter(service, new ObjectMapper());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null, List.of()));
        // 真实容器在解析阶段就丢弃超限分段：抛 IllegalStateException(SizeException)，不进 MVC
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/projects/project/skills") {
            @Override
            public Collection<Part> getParts() {
                throw new IllegalStateException("too large",
                        new FileSizeLimitExceededException("SKILL.md", 1024 * 1024 + 1, 1024 * 1024));
            }
        };
        request.addHeader("Idempotency-Key", "key-123");
        request.setContentType("multipart/form-data; boundary=first");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("UPLOAD_TOO_LARGE"));
        verify(chain, never()).doFilter(any(), any());
        verify(service, never()).save(any(), any(), any(), any(), any(), anyInt(), any(), any());
    }

    private MockHttpServletRequest multipart(String path, String boundary, byte[] file, String visibility) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.addHeader("Idempotency-Key", "key-123");
        request.setContentType("multipart/form-data; boundary=" + boundary);
        request.addPart(new MockPart("file", "skill.md", file));
        request.addPart(new MockPart("visibility", visibility.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return request;
    }

    @Test
    void jsonBodyStillReplaysAndRejectsChanges() throws Exception {
        IdempotencyService service = mock(IdempotencyService.class);
        IdempotencyFilter filter = new IdempotencyFilter(service, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        MockHttpServletRequest first = jsonRequest("{\"name\":\"one\"}");
        filter.doFilterInternal(first, new MockHttpServletResponse(), (req, res) -> {
            req.getInputStream().readAllBytes();
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204);
        });
        ArgumentCaptor<byte[]> hash = ArgumentCaptor.forClass(byte[].class);
        verify(service).save(eq(userId), any(), anyString(), eq("key-123"), hash.capture(),
                eq(204), eq(Map.of()), isNull());
        IdempotencyRecordEntity record = new IdempotencyRecordEntity();
        record.setRequestHash(hash.getValue());
        record.setResponseStatus(204);
        record.setResponseBodyRedacted(Map.of());
        when(service.find(any(), anyString(), eq("key-123"))).thenReturn(record);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse replay = new MockHttpServletResponse();
        filter.doFilterInternal(jsonRequest("{\"name\":\"one\"}"), replay, chain);
        assertEquals(204, replay.getStatus());
        MockHttpServletResponse conflict = new MockHttpServletResponse();
        filter.doFilterInternal(jsonRequest("{\"name\":\"two\"}"), conflict, chain);
        assertEquals(409, conflict.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    private MockHttpServletRequest jsonRequest(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/projects/project/skills");
        request.addHeader("Idempotency-Key", "key-123");
        request.setContentType("application/json");
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void cachesEmptyBodyFor204Response() throws ServletException, IOException {
        qg.qgent.common.service.IdempotencyService mockService = mock(qg.qgent.common.service.IdempotencyService.class);
        ObjectMapper mapper = new ObjectMapper();
        IdempotencyFilter filter = new IdempotencyFilter(mockService, mapper);

        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/projects/proj-1/members/user-1");
        request.addHeader("Idempotency-Key", "key-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UUID userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of())
        );

        FilterChain chain = (req, res) -> {
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204);
        };

        filter.doFilterInternal(request, response, chain);

        verify(mockService).save(eq(userId), any(), anyString(), eq("key-123"), any(), eq(204), eq(Map.of()), isNull());
    }

    @Test
    void replays204WithoutExecutingFilterChain() throws ServletException, IOException, Exception {
        qg.qgent.common.service.IdempotencyService mockService = mock(qg.qgent.common.service.IdempotencyService.class);
        ObjectMapper mapper = new ObjectMapper();
        IdempotencyFilter filter = new IdempotencyFilter(mockService, mapper);

        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/projects/proj-1/members/user-1");
        request.addHeader("Idempotency-Key", "key-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UUID userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of())
        );

        // Mock existing record
        qg.qgent.common.entity.IdempotencyRecordEntity existing = new qg.qgent.common.entity.IdempotencyRecordEntity();
        existing.setResponseStatus(204);
        existing.setResponseBodyRedacted(Map.of());
        existing.setRequestHash(MessageDigest.getInstance("SHA-256").digest(new byte[0]));

        when(mockService.find(any(), anyString(), eq("key-123"))).thenReturn(existing);

        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        // verify that the chain was NEVER executed
        verify(chain, never()).doFilter(any(), any());

        // verify response status is 204
        org.junit.jupiter.api.Assertions.assertEquals(204, response.getStatus());
    }

    @Test
    void replaysLargeBodyWithPrefixHashMatchingCachedPrefix() throws Exception {
        qg.qgent.common.service.IdempotencyService mockService = mock(qg.qgent.common.service.IdempotencyService.class);
        ObjectMapper mapper = new ObjectMapper();
        IdempotencyFilter filter = new IdempotencyFilter(mockService, mapper);

        UUID userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of())
        );

        // 超过 1MB 缓存上限的原始字节（模拟代理上传大文件）
        byte[] large = new byte[1024 * 1024 + 64];
        Arrays.fill(large, (byte) 'a');

        // 首次请求：204，幂等记录里保存的是前 1MB 前缀的哈希
        MockHttpServletRequest first = new MockHttpServletRequest("PUT",
                "/api/v1/projects/proj-1/attachments/att-1");
        first.addHeader("Idempotency-Key", "key-123");
        first.setContent(large);
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        // 还原真实链路：下游控制器会读取整个请求体，从而填满 ContentCachingRequestWrapper 的 1MB 缓存
        filter.doFilterInternal(first, firstResponse, (req, res) -> {
            req.getInputStream().readAllBytes();
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204);
        });

        ArgumentCaptor<byte[]> hashCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(mockService).save(eq(userId), any(), anyString(), eq("key-123"), hashCaptor.capture(),
                eq(204), eq(Map.of()), isNull());

        // 同键同 body 重试：应回放 204，且不因「全量哈希 vs 前缀哈希」不一致误报 409
        qg.qgent.common.entity.IdempotencyRecordEntity existing = new qg.qgent.common.entity.IdempotencyRecordEntity();
        existing.setResponseStatus(204);
        existing.setResponseBodyRedacted(Map.of());
        existing.setRequestHash(hashCaptor.getValue());
        when(mockService.find(any(), anyString(), eq("key-123"))).thenReturn(existing);

        MockHttpServletRequest retry = new MockHttpServletRequest("PUT",
                "/api/v1/projects/proj-1/attachments/att-1");
        retry.addHeader("Idempotency-Key", "key-123");
        retry.setContent(large);
        MockHttpServletResponse retryResponse = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(retry, retryResponse, chain);

        verify(chain, never()).doFilter(any(), any());
        org.junit.jupiter.api.Assertions.assertEquals(204, retryResponse.getStatus());
    }
}

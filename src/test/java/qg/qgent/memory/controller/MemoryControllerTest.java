package qg.qgent.memory.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import qg.qgent.memory.dto.MemoryCreateRequest;
import qg.qgent.memory.dto.MemoryResponse;
import qg.qgent.memory.service.MemoryService;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 删除专用 AI 草稿路由后，基础 Memory 接口仍正常提供服务。 */
class MemoryControllerTest {
    private final UUID actor = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final MemoryService service = mock(MemoryService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MemoryController(service))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aiDraftPostIsNoLongerMapped() throws Exception {
        mvc.perform(post("/api/v1/projects/{projectId}/memories/drafts", projectId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }

    @Test
    void manualCreateStillReturnsCreatedMemory() throws Exception {
        MemoryResponse memory = new MemoryResponse();
        memory.setTitle("项目约定");
        memory.setStatus("DRAFT");
        when(service.create(eq(actor), eq(projectId), any(MemoryCreateRequest.class))).thenReturn(memory);

        mvc.perform(post("/api/v1/projects/{projectId}/memories", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"项目约定\",\"content\":\"约定正文\",\"category\":\"GENERAL\",\"tags\":[]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.title").value("项目约定"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    void listReturnsMemoryWithoutAiMessageSourceFields() throws Exception {
        MemoryResponse memory = new MemoryResponse();
        memory.setTitle("项目约定");
        when(service.list(actor, projectId, null, null)).thenReturn(List.of(memory));

        mvc.perform(get("/api/v1/projects/{projectId}/memories", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("项目约定"))
                .andExpect(jsonPath("$.data[0].source").doesNotExist())
                .andExpect(jsonPath("$.data[0].sources").doesNotExist());
    }
}

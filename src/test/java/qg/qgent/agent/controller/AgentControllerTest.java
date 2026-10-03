package qg.qgent.agent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import qg.qgent.agent.dto.AgentResponse;
import qg.qgent.agent.dto.AgentReviewRejectRequest;
import qg.qgent.agent.service.AgentService;
import qg.qgent.agent.storage.AgentAvatarStorageService;
import qg.qgent.auth.security.CurrentActorProvider;
import qg.qgent.common.service.IdempotencyService;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentControllerTest {
    @Test
    void rejectWithoutBodyPassesOptionalReasonThroughIdempotency() {
        AgentService service = mock(AgentService.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        AgentController controller = new AgentController(service, mock(AgentAvatarStorageService.class),
                mock(CurrentActorProvider.class), idempotency);
        UUID actor = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        AgentResponse rejected = new AgentResponse();
        rejected.setVisibility("PRIVATE");
        when(service.reject(actor, teamId, agentId, null)).thenReturn(rejected);
        when(idempotency.execute(eq(actor), anyString(), eq("reject-key"), any(), eq(200),
                eq(AgentResponse.class), any())).thenAnswer(invocation -> {
            Map<?, ?> payload = invocation.getArgument(3);
            assertThat(payload.get("teamId")).isEqualTo(teamId);
            assertThat(payload.get("agentId")).isEqualTo(agentId);
            assertThat(((AgentReviewRejectRequest) payload.get("body")).getReason()).isNull();
            Supplier<AgentResponse> action = invocation.getArgument(6);
            return action.get();
        });

        var response = controller.reject(actor, teamId, agentId, "reject-key", null,
                new MockHttpServletRequest());

        assertThat(response.data()).isSameAs(rejected);
        verify(service).reject(actor, teamId, agentId, null);
    }
}

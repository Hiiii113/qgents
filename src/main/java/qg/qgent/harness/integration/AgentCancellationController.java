package qg.qgent.harness.integration;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.harness.service.AgentRunService;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;

import java.util.Map;
import java.util.UUID;

/** 将聊天中的停止请求适配到根运行取消，授权由执行生命周期服务核对。 */
@RestController
@RequiredArgsConstructor
public class AgentCancellationController {
    private final AgentRunService runs;

    @PostMapping("/api/v1/projects/{projectId}/groups/{groupId}/messages/{messageId}/cancel-agent")
    public ApiResponse<?> cancel(@AuthenticationPrincipal UUID actor, @PathVariable UUID projectId,
            @PathVariable UUID groupId, @PathVariable UUID messageId, HttpServletRequest request) {
        return ApiResponse.ok(Map.of("cancelledRuns", runs.cancel(actor, projectId, groupId, messageId)),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}

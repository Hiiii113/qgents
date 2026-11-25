package qg.qgent.harness.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.common.web.ApiException;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;
import qg.qgent.harness.dto.AgentRunSnapshot;
import qg.qgent.harness.runtime.AgentStreamService;

import java.util.List;

/**
 * 群内 Agent 运行的快照接口；实时增量经 WebSocket 推送。
 */
@RestController
@RequiredArgsConstructor
public class AgentRunController {
    private static final int MAX_RECENT_RUNS = 50;

    private final AgentStreamService streams;

    /**
     * 群内最近的运行（含子运行），用于刷新、断线恢复与降级轮询。
     */
    @GetMapping("/api/v1/projects/{projectId}/agent-runs")
    public ApiResponse<List<AgentRunSnapshot>> recent(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @RequestParam Long groupId,
        @RequestParam(defaultValue = "20") int limit,
        HttpServletRequest request) {
        if (limit < 1 || limit > MAX_RECENT_RUNS) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_LIMIT",
                "limit 必须为 1～" + MAX_RECENT_RUNS);
        }
        return ApiResponse.ok(
            streams.recentRuns(projectId, groupId, userId, limit),
            (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}

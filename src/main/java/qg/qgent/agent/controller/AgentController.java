package qg.qgent.agent.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.agent.dto.AgentApplicationRequest;
import qg.qgent.agent.dto.AgentResponse;
import qg.qgent.agent.service.AgentApplicationService;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;

import java.util.List;

/**
 * 项目内 Agent 接口：查询已启用 Agent 供群设置邀请、成员提交自定义 Agent 申请，
 * 以及项目管理员停用或重新启用 Agent。申请审批在群聊里由项目管理员完成，本接口不提供审批入口。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AgentController {

    private final AgentApplicationService applications;

    /**
     * 获取项目内已启用的 Agent
     */
    @GetMapping("/projects/{projectId}/agents")
    public ApiResponse<List<AgentResponse>> list(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long projectId,
        HttpServletRequest request) {
        return ok(
            applications
                .enabledAgents(actor, projectId)
                .stream()
                .map(AgentResponse::of)
                .toList(),
            request);
    }

    /**
     * 提交自定义 Agent 申请
     */
    @PostMapping("/projects/{projectId}/agents/applications")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AgentResponse> apply(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long projectId,
        @RequestBody AgentApplicationRequest body,
        HttpServletRequest request) {
        return ok(AgentResponse.of(applications.apply(actor, projectId, body)), request);
    }

    /**
     * 项目管理员停用已启用的 Agent；默认 Agent 不能停用。
     */
    @PostMapping("/projects/{projectId}/agents/{agentId}/disable")
    public ApiResponse<AgentResponse> disable(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long projectId,
        @PathVariable Long agentId,
        HttpServletRequest request) {
        return ok(AgentResponse.of(applications.disable(actor, projectId, agentId)), request);
    }

    /**
     * 项目管理员重新启用已停用的 Agent。
     */
    @PostMapping("/projects/{projectId}/agents/{agentId}/enable")
    public ApiResponse<AgentResponse> enable(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long projectId,
        @PathVariable Long agentId,
        HttpServletRequest request) {
        return ok(AgentResponse.of(applications.enable(actor, projectId, agentId)), request);
    }

    private <T> ApiResponse<T> ok(T value, HttpServletRequest request) {
        return ApiResponse.ok(value, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}

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
 * 项目内 Agent 接口：查询已启用 Agent 供群设置邀请，以及成员提交自定义 Agent 申请。
 * 申请由项目管理员在默认群回复公布消息并 @Agent 后审批，本接口不提供审批入口。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AgentController {

    private final AgentApplicationService applications;

    /** 项目内已启用的 Agent；只有它们能邀请入群，待审批申请不在其中。 */
    @GetMapping("/projects/{projectId}/agents")
    public ApiResponse<List<AgentResponse>> list(@AuthenticationPrincipal Long actor, @PathVariable Long projectId,
                                                 HttpServletRequest request) {
        return ok(applications.enabledAgents(actor, projectId).stream().map(AgentResponse::of).toList(), request);
    }

    /** 任一项目成员提交自定义 Agent 申请，保存为待审批；审批通过前不能邀请入群或运行。 */
    @PostMapping("/projects/{projectId}/agents/applications")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AgentResponse> apply(@AuthenticationPrincipal Long actor, @PathVariable Long projectId,
                                            @RequestBody AgentApplicationRequest body, HttpServletRequest request) {
        return ok(AgentResponse.of(applications.apply(actor, projectId, null, body)), request);
    }

    private <T> ApiResponse<T> ok(T value, HttpServletRequest request) {
        return ApiResponse.ok(value, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}

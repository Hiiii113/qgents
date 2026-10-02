package qg.qgent.agent.controller;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import qg.qgent.agent.dto.AgentGithubCapabilityRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import qg.qgent.agent.dto.AgentCreateRequest;
import qg.qgent.agent.dto.AgentResponse;
import qg.qgent.agent.dto.AgentReviewRejectRequest;
import qg.qgent.agent.dto.AgentUpdateRequest;
import qg.qgent.agent.service.AgentService;
import qg.qgent.agent.storage.AgentAvatarStorageService;
import qg.qgent.auth.security.CurrentActorProvider;
import qg.qgent.common.service.IdempotencyService;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;
import qg.qgent.user.dto.AvatarConfirmRequest;
import qg.qgent.user.dto.AvatarConfirmResponse;
import qg.qgent.user.dto.AvatarCredentialRequest;
import qg.qgent.user.dto.AvatarCredentialResponse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 团队 Agent 接口
 * 查询指定 Team 下的 Agent 列表资源，供前端展示团队内可用的 Agent。
 */
@RestController
@RequestMapping("/api/v1")
public class AgentController {
    private final AgentService service;
    private final AgentAvatarStorageService avatarStorage;
    private final CurrentActorProvider currentActor;
    private final IdempotencyService idempotency;

    public AgentController(AgentService service, AgentAvatarStorageService avatarStorage,
                           CurrentActorProvider currentActor, IdempotencyService idempotency) {
        this.service = service;
        this.avatarStorage = avatarStorage;
        this.currentActor = currentActor;
        this.idempotency = idempotency;
    }

    /**
     * 契约 §11.1：查询指定 Team 下的 Agent 列表。
     */
    @GetMapping("/teams/{teamId}/agents")
    public ApiResponse<List<AgentResponse>> list(@PathVariable UUID teamId, HttpServletRequest request) {
        return ApiResponse.ok(service.list(currentActor.currentUserId(), teamId),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 契约 v1.8.0 §22.4（前端联调）：获取单张 Agent 卡。
     * <p>
     * projectId 为可选 query：传了仅做两项校验——① 项目属于该 Agent 的团队（agents.team_id ==
     * projects.team_id）；
     * ② 当前用户对该项目有访问权（项目成员或 Team Owner 兜底）。校验失败返回 404（资源不可见），
     * 非法 UUID 返回 400，不返回 500。
     */
    @GetMapping("/teams/{teamId}/agents/{agentId}")
    public ApiResponse<AgentResponse> get(@PathVariable UUID teamId, @PathVariable UUID agentId,
            @Parameter(description = "可选：项目 ID，仅校验「项目属于该团队 + 当前用户有项目访问权」") @RequestParam(required = false) UUID projectId,
            HttpServletRequest request) {
        return ApiResponse.ok(service.get(teamId, agentId, currentActor.currentUserId(), projectId),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 契约 §11.1（接口补充 v2.0.3 §2）：创建自定义 Agent（PRIVATE，仅团队成员）。
     * 同一 Idempotency-Key 重试返回第一次成功结果；不同请求体复用同一 Key 返回 409。
     */
    @PostMapping("/teams/{teamId}/agents")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AgentResponse> create(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AgentCreateRequest body, HttpServletRequest request) {
        AgentResponse result = idempotency.execute(actor, "POST:/teams/{teamId}/agents", key, body, 201,
                AgentResponse.class, () -> service.create(actor, teamId, body));
        return ApiResponse.ok(result, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 契约 §11.1（接口补充 v2.0.3 §3）：编辑自定义 Agent（仅创建者，系统预置不可编辑）。
     */
    @PatchMapping("/teams/{teamId}/agents/{agentId}")
    public ApiResponse<AgentResponse> update(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
            @PathVariable UUID agentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AgentUpdateRequest body, HttpServletRequest request) {
        AgentResponse result = idempotency.execute(actor, "PATCH:/teams/{teamId}/agents/{agentId}", key,
                Map.of("teamId", teamId, "agentId", agentId, "body", body), 200, AgentResponse.class,
                () -> service.update(actor, teamId, agentId, body));
        return ApiResponse.ok(result, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    @PutMapping("/teams/{teamId}/agents/{agentId}/github-capability")
    public ApiResponse<AgentResponse> configureGithub(@AuthenticationPrincipal UUID actor,
            @PathVariable UUID teamId, @PathVariable UUID agentId,
            @Valid @RequestBody AgentGithubCapabilityRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(service.configureGithub(actor, teamId, agentId, body.getEnabled()),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 契约 §11.1：发布自定义 Agent（仅创建者）；普通成员进入 PENDING，Team Owner 自建 Agent
     * 直接发布为 TEAM，无需额外 approve。
     */
    @PostMapping("/teams/{teamId}/agents/{agentId}/publish")
    public ApiResponse<AgentResponse> publish(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
            @PathVariable UUID agentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        AgentResponse result = idempotency.execute(actor, "POST:/teams/{teamId}/agents/{agentId}/publish", key,
                Map.of("teamId", teamId, "agentId", agentId), 200, AgentResponse.class,
                () -> service.publish(actor, teamId, agentId));
        return ApiResponse.ok(result, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 契约 §11.1（v2.0.6 审核化）：批准发布（Team Owner，PENDING+ACTIVE → TEAM+ACTIVE）。
     * 批准后团队共享，不可再收回为私有（只能归档）。
     */
    @PostMapping("/teams/{teamId}/agents/{agentId}/approve")
    public ApiResponse<AgentResponse> approve(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
            @PathVariable UUID agentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        AgentResponse result = idempotency.execute(actor, "POST:/teams/{teamId}/agents/{agentId}/approve", key,
                Map.of("teamId", teamId, "agentId", agentId), 200, AgentResponse.class,
                () -> service.approve(actor, teamId, agentId));
        return ApiResponse.ok(result, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 契约 §11.1（v2.0.6 审核化）：拒绝发布（Team Owner，PENDING+ACTIVE → PRIVATE+ACTIVE，
     * 写入拒绝原因供创建者查看后修正重新提交）。
     */
    @PostMapping("/teams/{teamId}/agents/{agentId}/reject")
    public ApiResponse<AgentResponse> reject(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
            @PathVariable UUID agentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody(required = false) AgentReviewRejectRequest body, HttpServletRequest request) {
        AgentResponse result = idempotency.execute(actor, "POST:/teams/{teamId}/agents/{agentId}/reject", key,
                Map.of("teamId", teamId, "agentId", agentId, "body", body), 200, AgentResponse.class,
                () -> service.reject(actor, teamId, agentId, body == null ? null : body.getReason()));
        return ApiResponse.ok(result, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 契约 §11.1（接口补充 v2.0.3 §6）：归档 Agent（创建者或 Team Owner，PRIVATE/TEAM+ACTIVE → ARCHIVED）。
     */
    @PostMapping("/teams/{teamId}/agents/{agentId}/archive")
    public ApiResponse<AgentResponse> archive(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
            @PathVariable UUID agentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        AgentResponse result = idempotency.execute(actor, "POST:/teams/{teamId}/agents/{agentId}/archive", key,
                Map.of("teamId", teamId, "agentId", agentId), 200, AgentResponse.class,
                () -> service.archive(actor, teamId, agentId));
        return ApiResponse.ok(result, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 签发 Agent 头像直传凭证（团队成员；对象键 agents/{teamId}/{uuid}.{ext}）。
     */
    @PostMapping("/teams/{teamId}/agents/avatar/credential")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AvatarCredentialResponse> avatarCredential(@PathVariable UUID teamId,
            @AuthenticationPrincipal UUID actor, @Valid @RequestBody AvatarCredentialRequest body,
            HttpServletRequest request) {
        service.requireTeamMember(teamId, actor);
        AgentAvatarStorageService.AgentAvatarCredential credential =
                avatarStorage.createCredential(teamId, body.getMediaType(), body.getSizeBytes());
        return ApiResponse.ok(new AvatarCredentialResponse(credential.objectKey(),
                credential.credential().getUploadUrl(), credential.credential().getMethod(),
                credential.credential().getHeaders(), credential.credential().getExpiresAt()),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 确认 Agent 头像上传并返回公共读 URL（不写任何用户字段）。
     */
    @PostMapping("/teams/{teamId}/agents/avatar/confirm")
    public ApiResponse<AvatarConfirmResponse> avatarConfirm(@PathVariable UUID teamId,
            @AuthenticationPrincipal UUID actor, @Valid @RequestBody AvatarConfirmRequest body,
            HttpServletRequest request) {
        service.requireTeamMember(teamId, actor);
        String avatarUrl = avatarStorage.confirmAvatar(teamId, body.getObjectKey());
        return ApiResponse.ok(new AvatarConfirmResponse(avatarUrl),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}

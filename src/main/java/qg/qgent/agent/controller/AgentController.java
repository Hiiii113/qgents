package qg.qgent.agent.controller;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import qg.qgent.agent.dto.AgentCreateRequest;
import qg.qgent.agent.dto.AgentGithubCapabilityRequest;
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

/** 团队 Agent 的查询、生命周期、GitHub 能力与头像接口。 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AgentController {
        private final AgentService service;
        private final AgentAvatarStorageService avatarStorage;
        private final CurrentActorProvider currentActor;
        private final IdempotencyService idempotency;

        /** 查询当前用户可见的团队 Agent。 */
        @GetMapping("/teams/{teamId}/agents")
        public ApiResponse<List<AgentResponse>> list(@PathVariable UUID teamId, HttpServletRequest request) {
                return ApiResponse.ok(service.list(currentActor.currentUserId(), teamId),
                                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
        }

        /** 获取 Agent 详情；传入 projectId 时额外校验项目归属和访问权限。 */
        @GetMapping("/teams/{teamId}/agents/{agentId}")
        public ApiResponse<AgentResponse> get(@PathVariable UUID teamId, @PathVariable UUID agentId,
                        @Parameter(description = "可选：项目 ID，仅校验「项目属于该团队 + 当前用户有项目访问权」") @RequestParam(required = false) UUID projectId,
                        HttpServletRequest request) {
                return ApiResponse.ok(service.get(teamId, agentId, currentActor.currentUserId(), projectId),
                                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
        }

        /** 创建私有 Agent；相同幂等键重试返回原结果。 */
        @PostMapping("/teams/{teamId}/agents")
        @ResponseStatus(HttpStatus.CREATED)
        public ApiResponse<AgentResponse> create(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
                        @RequestHeader(value = "Idempotency-Key", required = false) String key,
                        @Valid @RequestBody AgentCreateRequest body, HttpServletRequest request) {
                AgentResponse result = idempotency.execute(actor, "POST:/teams/{teamId}/agents", key, body, 201,
                                AgentResponse.class, () -> service.create(actor, teamId, body));
                return ApiResponse.ok(result, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
        }

        /** 创建者编辑自定义 Agent；团队所有者可编辑默认 Agent 的昵称与头像。 */
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

        /** 提交发布；团队所有者创建的 Agent 直接共享，其他成员提交审核。 */
        @PostMapping("/teams/{teamId}/agents/{agentId}/publish")
        public ApiResponse<AgentResponse> publish(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
                        @PathVariable UUID agentId,
                        @RequestHeader(value = "Idempotency-Key", required = false) String key,
                        HttpServletRequest request) {
                AgentResponse result = idempotency.execute(actor, "POST:/teams/{teamId}/agents/{agentId}/publish", key,
                                Map.of("teamId", teamId, "agentId", agentId), 200, AgentResponse.class,
                                () -> service.publish(actor, teamId, agentId));
                return ApiResponse.ok(result, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
        }

        /** 团队所有者批准待审核 Agent，发布后不能改回私有。 */
        @PostMapping("/teams/{teamId}/agents/{agentId}/approve")
        public ApiResponse<AgentResponse> approve(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
                        @PathVariable UUID agentId,
                        @RequestHeader(value = "Idempotency-Key", required = false) String key,
                        HttpServletRequest request) {
                AgentResponse result = idempotency.execute(actor, "POST:/teams/{teamId}/agents/{agentId}/approve", key,
                                Map.of("teamId", teamId, "agentId", agentId), 200, AgentResponse.class,
                                () -> service.approve(actor, teamId, agentId));
                return ApiResponse.ok(result, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
        }

        /** 团队所有者拒绝发布，Agent 恢复私有，可修改后重新提交。 */
        @PostMapping("/teams/{teamId}/agents/{agentId}/reject")
        public ApiResponse<AgentResponse> reject(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
                        @PathVariable UUID agentId,
                        @RequestHeader(value = "Idempotency-Key", required = false) String key,
                        @Valid @RequestBody(required = false) AgentReviewRejectRequest body,
                        HttpServletRequest request) {
                AgentReviewRejectRequest review = body == null ? new AgentReviewRejectRequest() : body;
                AgentResponse result = idempotency.execute(actor, "POST:/teams/{teamId}/agents/{agentId}/reject", key,
                                Map.of("teamId", teamId, "agentId", agentId, "body", review), 200, AgentResponse.class,
                                () -> service.reject(actor, teamId, agentId, review.getReason()));
                return ApiResponse.ok(result, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
        }

        /** 创建者或团队所有者归档 Agent，后续不能启动新运行。 */
        @PostMapping("/teams/{teamId}/agents/{agentId}/archive")
        public ApiResponse<AgentResponse> archive(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
                        @PathVariable UUID agentId,
                        @RequestHeader(value = "Idempotency-Key", required = false) String key,
                        HttpServletRequest request) {
                AgentResponse result = idempotency.execute(actor, "POST:/teams/{teamId}/agents/{agentId}/archive", key,
                                Map.of("teamId", teamId, "agentId", agentId), 200, AgentResponse.class,
                                () -> service.archive(actor, teamId, agentId));
                return ApiResponse.ok(result, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
        }

        /** 为团队成员签发 Agent 头像的 OSS 上传凭证。 */
        @PostMapping("/teams/{teamId}/agents/avatar/credential")
        @ResponseStatus(HttpStatus.CREATED)
        public ApiResponse<AvatarCredentialResponse> avatarCredential(@PathVariable UUID teamId,
                        @AuthenticationPrincipal UUID actor, @Valid @RequestBody AvatarCredentialRequest body,
                        HttpServletRequest request) {
                service.requireTeamMember(teamId, actor);
                AgentAvatarStorageService.AgentAvatarCredential credential = avatarStorage.createCredential(teamId,
                                body.getMediaType(), body.getSizeBytes());
                return ApiResponse.ok(new AvatarCredentialResponse(credential.objectKey(),
                                credential.credential().getUploadUrl(), credential.credential().getMethod(),
                                credential.credential().getHeaders(), credential.credential().getExpiresAt()),
                                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
        }

        /** 确认头像已上传并返回公共访问地址。 */
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

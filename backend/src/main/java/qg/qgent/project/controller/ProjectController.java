package qg.qgent.project.controller;

import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import qg.qgent.common.dto.PageSlice;
import qg.qgent.common.service.IdempotencyService;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.PagedApiResponse;
import qg.qgent.common.web.RequestIdFilter;
import qg.qgent.project.dto.ProjectCreateRequest;
import qg.qgent.project.dto.ProjectMemberAddRequest;
import qg.qgent.project.dto.ProjectMemberResponse;
import qg.qgent.project.dto.ProjectMemberUpdateRequest;
import qg.qgent.project.dto.ProjectResponse;
import qg.qgent.project.dto.ProjectUpdateRequest;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.project.service.ProjectService;
import qg.qgent.project.storage.ProjectAvatarStorageService;
import qg.qgent.user.dto.AvatarConfirmRequest;
import qg.qgent.user.dto.AvatarConfirmResponse;
import qg.qgent.user.dto.AvatarCredentialRequest;
import qg.qgent.user.dto.AvatarCredentialResponse;

import java.util.Map;
import java.util.UUID;

/** 项目与项目成员接口 项目资料的创建、查询、修改、归档/恢复与项目成员管理。 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ProjectController {
    private final ProjectService projects;
    private final IdempotencyService idempotency;
    private final ProjectAccessService access;
    private final ProjectAvatarStorageService avatarStorage;

    /** 创建项目，创建者自动成为 PROJECT_ADMIN。 */
    @PostMapping("/teams/{teamId}/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectResponse> create(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @Valid @RequestBody ProjectCreateRequest body, HttpServletRequest request) {
        ProjectResponse value = idempotency.execute(actor, "POST:/teams/{teamId}/projects", key,
                Map.of("teamId", teamId, "body", body), 201, ProjectResponse.class,
                () -> projects.create(actor, teamId, body));
        return ok(value, request);
    }

    /** 分页获取当前用户可见的项目。 */
    @GetMapping("/teams/{teamId}/projects")
    public PagedApiResponse<ProjectResponse> list(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
                                                  @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
                                                  HttpServletRequest request) {
        return page(projects.list(actor, teamId, cursor, limit), request);
    }

    /** 获取项目资料。 */
    @GetMapping("/projects/{projectId}")
    public ApiResponse<ProjectResponse> get(@AuthenticationPrincipal UUID actor, @PathVariable UUID projectId,
                                            HttpServletRequest request) {
        return ok(projects.get(actor, projectId), request);
    }

    /** 修改项目资料（Project Admin 或 Team Owner）。 */
    @PatchMapping("/projects/{projectId}")
    public ApiResponse<ProjectResponse> update(@AuthenticationPrincipal UUID actor, @PathVariable UUID projectId,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @Valid @RequestBody ProjectUpdateRequest body, HttpServletRequest request) {
        ProjectResponse value = idempotency.execute(actor, "PATCH:/projects/{projectId}", key,
                Map.of("projectId", projectId, "body", body), 200, ProjectResponse.class,
                () -> projects.update(actor, projectId, body));
        return ok(value, request);
    }

    /** 签发项目头像直传凭证（项目成员；对象键 projects/{projectId}/{uuid}.{ext}）。 */
    @PostMapping("/projects/{projectId}/avatar/credential")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AvatarCredentialResponse> avatarCredential(@PathVariable UUID projectId,
                                                                  @AuthenticationPrincipal UUID actor,
                                                                  @Valid @RequestBody AvatarCredentialRequest body,
                                                                  HttpServletRequest request) {
        access.requireProjectMember(projectId, actor);
        ProjectAvatarStorageService.ProjectAvatarCredential credential =
                avatarStorage.createCredential(projectId, body.getMediaType(), body.getSizeBytes());
        return ok(new AvatarCredentialResponse(credential.objectKey(),
                credential.credential().getUploadUrl(), credential.credential().getMethod(),
                credential.credential().getHeaders(), credential.credential().getExpiresAt()), request);
    }

    /** 确认上传并返回头像 URL；客户端再通过修改项目接口保存 avatarUrl。 */
    @PostMapping("/projects/{projectId}/avatar/confirm")
    public ApiResponse<AvatarConfirmResponse> avatarConfirm(@PathVariable UUID projectId,
                                                            @AuthenticationPrincipal UUID actor,
                                                            @Valid @RequestBody AvatarConfirmRequest body,
                                                            HttpServletRequest request) {
        access.requireProjectMember(projectId, actor);
        String avatarUrl = avatarStorage.confirmAvatar(projectId, body.getObjectKey());
        return ok(new AvatarConfirmResponse(avatarUrl), request);
    }

    /** 归档项目。 */
    @PostMapping("/projects/{projectId}/archive")
    public ApiResponse<ProjectResponse> archive(@AuthenticationPrincipal UUID actor, @PathVariable UUID projectId,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        ProjectResponse value = idempotency.execute(actor, "POST:/projects/{projectId}/archive", key,
                Map.of("projectId", projectId), 200, ProjectResponse.class,
                () -> projects.archive(actor, projectId));
        return ok(value, request);
    }

    /** 恢复已归档项目。 */
    @PostMapping("/projects/{projectId}/restore")
    public ApiResponse<ProjectResponse> restore(@AuthenticationPrincipal UUID actor, @PathVariable UUID projectId,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        ProjectResponse value = idempotency.execute(actor, "POST:/projects/{projectId}/restore", key,
                Map.of("projectId", projectId), 200, ProjectResponse.class,
                () -> projects.restore(actor, projectId));
        return ok(value, request);
    }

    /** 分页获取项目成员与角色。 */
    @GetMapping("/projects/{projectId}/members")
    public PagedApiResponse<ProjectMemberResponse> members(@AuthenticationPrincipal UUID actor,
                                                           @PathVariable UUID projectId, @RequestParam(required = false) String cursor,
                                                           @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return page(projects.members(actor, projectId, cursor, limit), request);
    }

    /** 将团队现有成员加入项目（初始 PROJECT_MEMBER）。 */
    @PostMapping("/projects/{projectId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectMemberResponse> addMember(@AuthenticationPrincipal UUID actor,
                                                        @PathVariable UUID projectId, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                        @Valid @RequestBody ProjectMemberAddRequest body, HttpServletRequest request) {
        ProjectMemberResponse value = idempotency.execute(actor, "POST:/projects/{projectId}/members", key,
                Map.of("projectId", projectId, "body", body), 201, ProjectMemberResponse.class,
                () -> projects.addMember(actor, projectId, body));
        return ok(value, request);
    }

    /** 调整项目成员角色（管理员设置后不可降级）。 */
    @PatchMapping("/projects/{projectId}/members/{userId}")
    public ApiResponse<ProjectMemberResponse> updateMember(@AuthenticationPrincipal UUID actor,
                                                           @PathVariable UUID projectId, @PathVariable UUID userId,
                                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                           @Valid @RequestBody ProjectMemberUpdateRequest body, HttpServletRequest request) {
        ProjectMemberResponse value = idempotency.execute(actor,
                "PATCH:/projects/{projectId}/members/{userId}", key,
                Map.of("projectId", projectId, "userId", userId, "body", body), 200,
                ProjectMemberResponse.class, () -> projects.updateMember(actor, projectId, userId, body));
        return ok(value, request);
    }

    /** 移除项目成员。 */
    @DeleteMapping("/projects/{projectId}/members/{userId}")
    public ApiResponse<ProjectMemberResponse> removeMember(@AuthenticationPrincipal UUID actor,
                                                           @PathVariable UUID projectId, @PathVariable UUID userId,
                                                           @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        ProjectMemberResponse value = idempotency.execute(actor,
                "DELETE:/projects/{projectId}/members/{userId}", key,
                Map.of("projectId", projectId, "userId", userId), 200, ProjectMemberResponse.class,
                () -> projects.removeMember(actor, projectId, userId));
        return ok(value, request);
    }

    private <T> ApiResponse<T> ok(T value, HttpServletRequest request) {
        return ApiResponse.ok(value, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    private <T> PagedApiResponse<T> page(PageSlice<T> value, HttpServletRequest request) {
        return new PagedApiResponse<>(value.getData(), value.getPage(),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}

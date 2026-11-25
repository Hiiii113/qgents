package qg.qgent.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;
import qg.qgent.user.dto.AvatarConfirmRequest;
import qg.qgent.user.dto.AvatarConfirmResponse;
import qg.qgent.user.dto.AvatarCredentialRequest;
import qg.qgent.user.dto.AvatarCredentialResponse;
import qg.qgent.user.dto.CurrentTeamRequest;
import qg.qgent.user.dto.UserUpdateRequest;
import qg.qgent.user.service.AvatarService;
import qg.qgent.user.service.UserService;

import java.util.Map;

/**
 * 当前账户接口：账户摘要、资料修改、当前团队与头像上传。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AvatarService avatarService;

    /**
     * 获取当前账户、团队与项目角色摘要。
     */
    @GetMapping("/me")
    public ApiResponse<?> me(@AuthenticationPrincipal Long userId, HttpServletRequest request) {
        return ok(userService.me(userId), request);
    }

    /**
     * 修改昵称或头像。
     */
    @PatchMapping("/me")
    public ApiResponse<?> updateMe(
        @AuthenticationPrincipal Long userId,
        @Valid @RequestBody UserUpdateRequest body,
        HttpServletRequest request) {
        return ok(userService.updateMe(userId, body), request);
    }

    /**
     * 保存当前团队，供后续登录和其他设备恢复。
     */
    @PutMapping("/me/current-team")
    public ApiResponse<?> selectTeam(
        @AuthenticationPrincipal Long userId,
        @Valid @RequestBody CurrentTeamRequest body,
        HttpServletRequest request) {
        userService.selectTeam(userId, body.teamId());
        return ok(Map.of(), request);
    }

    /**
     * 签发头像直传凭证：客户端凭 uploadUrl 直传 OSS，再携带返回的 objectKey 调确认接口。
     */
    @Operation(summary = "签发头像直传凭证", description = "返回头像对象键与预签名上传地址；OSS 未启用时返回 501。")
    @PostMapping("/me/avatar/credential")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> createAvatarCredential(
        @AuthenticationPrincipal Long userId,
        @Valid @RequestBody AvatarCredentialRequest body,
        HttpServletRequest request) {
        AvatarCredentialResponse data =
            avatarService.credential(userId, body.getMediaType(), body.getSizeBytes());
        return ok(data, request);
    }

    /**
     * 确认头像上传：校验对象属于当前用户且已真实上传，写入 users.avatar_url，返回公共读长期 URL。
     */
    @Operation(summary = "确认头像上传完成", description = "校验对象存在后写库并返回头像公共读 URL；OSS 未启用时返回 501。")
    @PostMapping("/me/avatar/confirm")
    public ApiResponse<?> confirmAvatar(
        @AuthenticationPrincipal Long userId,
        @Valid @RequestBody AvatarConfirmRequest body,
        HttpServletRequest request) {
        AvatarConfirmResponse data = avatarService.confirm(userId, body);
        return ok(data, request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}

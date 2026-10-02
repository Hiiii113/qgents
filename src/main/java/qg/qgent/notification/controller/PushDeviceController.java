package qg.qgent.notification.controller;

import lombok.RequiredArgsConstructor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import qg.qgent.common.service.IdempotencyService;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;
import qg.qgent.notification.dto.PushDeviceRegistrationRequest;
import qg.qgent.notification.dto.PushDeviceResponse;
import qg.qgent.notification.service.PushNotificationService;

import java.util.Map;
import java.util.UUID;

/** 移动端 FCM 设备注册与注销接口。Token 不在响应中回显。 */
@RestController
@RequestMapping("/api/v1/push/devices")
@RequiredArgsConstructor
public class PushDeviceController {
    private final PushNotificationService push;
    private final IdempotencyService idempotency;

    /** 注册或刷新当前用户的一台 Android/iOS 安装实例。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> register(@AuthenticationPrincipal UUID userId,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                   @Valid @RequestBody PushDeviceRegistrationRequest body,
                                   HttpServletRequest request) {
        PushDeviceResponse result = idempotency.execute(userId, "POST:/push/devices", idempotencyKey,
                body, 201, PushDeviceResponse.class, () -> push.register(userId, body));
        return ApiResponse.ok(result, requestId(request));
    }

    /** 幂等停用当前用户指定的安装实例。 */
    @DeleteMapping("/{installationId}")
    public ApiResponse<?> unregister(@AuthenticationPrincipal UUID userId,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                     @PathVariable String installationId, HttpServletRequest request) {
        idempotency.execute(userId, "DELETE:/push/devices/{installationId}", idempotencyKey,
                Map.of("installationId", installationId), 200, Object.class, () -> {
                    push.unregister(userId, installationId);
                    return Map.of();
                });
        return ApiResponse.ok(Map.of(), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
    }
}

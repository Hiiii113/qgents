package qg.qgent.mergerequest.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;
import qg.qgent.mergerequest.dto.MergeRequestCreateRequest;
import qg.qgent.mergerequest.service.MergeRequestService;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects/{projectId}/merge-requests")
public class MergeRequestController {
    private final MergeRequestService service;

    @PostMapping
    public ApiResponse<?> create(@PathVariable UUID projectId, @AuthenticationPrincipal UUID actor,
                                 @Valid @RequestBody MergeRequestCreateRequest body, HttpServletRequest request) {
        return ok(service.create(projectId,actor,body.getTaskId(),body.getRepositoryId(),body.getTargetBranch(),body.getTitle(),body.getBody()),request);
    }

    @GetMapping
    public ApiResponse<?> list(@PathVariable UUID projectId,@AuthenticationPrincipal UUID actor,HttpServletRequest request) {
        return ok(service.list(projectId,actor),request);
    }

    @GetMapping("/{id}")
    public ApiResponse<?> detail(@PathVariable UUID projectId,@PathVariable UUID id,@AuthenticationPrincipal UUID actor,HttpServletRequest request) {
        return ok(service.detail(projectId,actor,id),request);
    }

    private ApiResponse<?> ok(Object data,HttpServletRequest request) { return ApiResponse.ok(data,(String)request.getAttribute(RequestIdFilter.ATTRIBUTE)); }
}

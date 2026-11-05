package qg.qgent.memory.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import qg.qgent.common.dto.RejectRequest;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;
import qg.qgent.memory.dto.MemoryCreateRequest;
import qg.qgent.memory.dto.MemoryUpdateRequest;
import qg.qgent.memory.service.MemoryService;


/** 共享 Memory 接口 */
@RestController
@RequestMapping("/api/v1")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /** 查询可见 Memory，支持状态、标签过滤。 */
    @GetMapping("/projects/{projectId}/memories")
    public ApiResponse<?> list(@AuthenticationPrincipal Long userId, @PathVariable Long projectId,
                               @RequestParam(value = "status", required = false) String status,
                               @RequestParam(value = "tag", required = false) String tag, HttpServletRequest request) {
        return ok(memoryService.list(userId, projectId, status, tag), request);
    }

    /** 手动创建 Memory；管理员直接发布，普通成员保存草稿。 */
    @PostMapping("/projects/{projectId}/memories")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> create(@AuthenticationPrincipal Long userId, @PathVariable Long projectId,
                                 @Valid @RequestBody MemoryCreateRequest body, HttpServletRequest request) {
        return ok(memoryService.create(userId, projectId, body), request);
    }

    /** 获取 Memory 详情。 */
    @GetMapping("/projects/{projectId}/memories/{memoryId}")
    public ApiResponse<?> get(@AuthenticationPrincipal Long userId, @PathVariable Long projectId,
                              @PathVariable Long memoryId, HttpServletRequest request) {
        return ok(memoryService.get(userId, projectId, memoryId), request);
    }

    /** 编辑草稿或审核中内容。 */
    @PatchMapping("/projects/{projectId}/memories/{memoryId}")
    public ApiResponse<?> update(@AuthenticationPrincipal Long userId, @PathVariable Long projectId,
                                 @PathVariable Long memoryId, @Valid @RequestBody MemoryUpdateRequest body, HttpServletRequest request) {
        return ok(memoryService.update(userId, projectId, memoryId, body), request);
    }

    /** 提交 Memory 审核。 */
    @PostMapping("/projects/{projectId}/memories/{memoryId}/submit-review")
    public ApiResponse<?> submitReview(@AuthenticationPrincipal Long userId, @PathVariable Long projectId,
                                       @PathVariable Long memoryId, HttpServletRequest request) {
        return ok(memoryService.submitReview(userId, projectId, memoryId), request);
    }

    /** 批准并发布 Memory（Project Admin）。 */
    @PostMapping("/projects/{projectId}/memories/{memoryId}/approve")
    public ApiResponse<?> approve(@AuthenticationPrincipal Long userId, @PathVariable Long projectId,
                                  @PathVariable Long memoryId, HttpServletRequest request) {
        return ok(memoryService.approve(userId, projectId, memoryId), request);
    }

    /** 拒绝 Memory 并给出原因（Project Admin）。 */
    @PostMapping("/projects/{projectId}/memories/{memoryId}/reject")
    public ApiResponse<?> reject(@AuthenticationPrincipal Long userId, @PathVariable Long projectId,
                                 @PathVariable Long memoryId, @Valid @RequestBody RejectRequest body, HttpServletRequest request) {
        return ok(memoryService.reject(userId, projectId, memoryId, body.getReason()), request);
    }

    /** 归档 Memory（Project Admin）。 */
    @PostMapping("/projects/{projectId}/memories/{memoryId}/archive")
    public ApiResponse<?> archive(@AuthenticationPrincipal Long userId, @PathVariable Long projectId,
                                  @PathVariable Long memoryId, HttpServletRequest request) {
        return ok(memoryService.archive(userId, projectId, memoryId), request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}

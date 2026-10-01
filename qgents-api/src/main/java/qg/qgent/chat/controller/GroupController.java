package qg.qgent.chat.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import qg.qgent.chat.dto.GroupPinRequest;
import qg.qgent.chat.dto.GroupUpdateRequest;
import qg.qgent.chat.service.GroupService;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping("/projects/{projectId}/groups")
    public ApiResponse<?> list(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                               HttpServletRequest request) {
        return ok(groupService.list(userId, projectId), request);
    }

    @GetMapping("/chat/groups")
    public ApiResponse<?> allGroups(@AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        return ok(groupService.allGroups(userId), request);
    }

    @GetMapping("/projects/{projectId}/groups/{groupId}")
    public ApiResponse<?> get(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                              @PathVariable UUID groupId, HttpServletRequest request) {
        return ok(groupService.get(userId, projectId, groupId), request);
    }

    @PatchMapping("/projects/{projectId}/groups/{groupId}")
    public ApiResponse<?> update(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                 @PathVariable UUID groupId, @Valid @RequestBody GroupUpdateRequest body, HttpServletRequest request) {
        return ok(groupService.update(userId, projectId, groupId, body), request);
    }

    @GetMapping("/projects/{projectId}/groups/{groupId}/members")
    public ApiResponse<?> members(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                  @PathVariable UUID groupId, HttpServletRequest request) {
        return ok(groupService.members(userId, projectId, groupId), request);
    }

    @PostMapping("/projects/{projectId}/groups/{groupId}/read")
    public ApiResponse<?> markRead(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                   @PathVariable UUID groupId,
                                   @RequestHeader("Idempotency-Key") String idempotencyKey,
                                   HttpServletRequest request) {
        return ok(groupService.markRead(userId, projectId, groupId), request);
    }

    @PutMapping("/projects/{projectId}/groups/{groupId}/pin")
    public ApiResponse<?> setPinned(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                    @PathVariable UUID groupId, @Valid @RequestBody GroupPinRequest body,
                                    HttpServletRequest request) {
        return ok(groupService.setPinned(userId, projectId, groupId, body.getPinned()), request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}

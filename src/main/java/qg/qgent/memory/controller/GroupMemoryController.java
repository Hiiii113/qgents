package qg.qgent.memory.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;
import qg.qgent.memory.dto.GroupMemoryResponse;
import qg.qgent.memory.service.GroupMemoryService;

/** 群聊记忆只读接口：正文由后台提炼维护，这里只供排查查看。 */
@RestController
@RequestMapping("/api/v1")
public class GroupMemoryController {

    private final GroupMemoryService memories;

    public GroupMemoryController(GroupMemoryService memories) {
        this.memories = memories;
    }

    @Operation(summary = "读取群聊记忆", description = "仅项目管理员且属于来源群时可读；群记忆只由后台提炼更新，成员通过对话纠错。")
    @GetMapping("/projects/{projectId}/groups/{groupId}/memory")
    public ApiResponse<GroupMemoryResponse> get(@AuthenticationPrincipal Long userId, @PathVariable Long projectId,
                                                @PathVariable Long groupId, HttpServletRequest request) {
        return ApiResponse.ok(memories.get(userId, projectId, groupId),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}

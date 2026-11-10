package qg.qgent.memory.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.memory.service.ProjectMemoryService;

/** 项目长期记忆读取接口，正文存放在项目目录的 memory.md；更新只在群聊中审批后生效。 */
@RestController
@RequestMapping("/api/v1")
public class MemoryController {

    private final ProjectMemoryService memories;

    public MemoryController(ProjectMemoryService memories) {
        this.memories = memories;
    }

    @Operation(summary = "读取项目长期记忆", description = "返回 memory.md 全文、内容版本和最后修改时间；项目成员均可读取。")
    @GetMapping("/projects/{projectId}/memory")
    public ApiResponse<ProjectMemoryResponse> get(@AuthenticationPrincipal Long userId, @PathVariable Long projectId,
                                                  HttpServletRequest request) {
        return ok(memories.get(userId, projectId), request);
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}

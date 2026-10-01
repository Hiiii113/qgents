package qg.qgent.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import qg.qgent.entity.AgentRunEntity;
import qg.qgent.mapper.AgentRunMapper;
import qg.qgent.service.GroupService;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;

import java.util.UUID;

/** 聊天运行与 subagent 结果，不依赖工作任务存在。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/groups/{groupId}/agent-runs")
public class AgentRunController {
    private final AgentRunMapper runs;
    private final GroupService groups;

    public AgentRunController(AgentRunMapper runs, GroupService groups) {
        this.runs = runs;
        this.groups = groups;
    }

    @GetMapping
    public ApiResponse<?> list(@PathVariable UUID projectId, @PathVariable UUID groupId,
                               @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        groups.requireGroupMember(projectId, groupId, actor);
        return ApiResponse.ok(runs.selectList(Wrappers.<AgentRunEntity>lambdaQuery()
                        .eq(AgentRunEntity::getProjectId, projectId).eq(AgentRunEntity::getGroupId, groupId)
                        .orderByDesc(AgentRunEntity::getCreatedAt).last("LIMIT 100")),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}

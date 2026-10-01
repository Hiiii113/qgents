package qg.qgent.task.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import qg.qgent.agent.runtime.AgentChatService;
import qg.qgent.common.web.ApiException;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.task.dto.TaskCreateRequest;
import qg.qgent.task.dto.TaskResponse;
import qg.qgent.task.entity.TaskEntity;
import qg.qgent.task.mapper.TaskMapper;
import qg.qgent.task.service.TaskService;

import java.util.UUID;

/** 可选工作任务的读写入口；执行记录在 agent-runs，不再暴露冻结步骤和阶段运行。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks")
public class TaskController {
    private final TaskService service;
    private final TaskMapper tasks;
    private final ProjectAccessService access;
    private final AgentChatService agent;

    public TaskController(TaskService service, TaskMapper tasks, ProjectAccessService access, AgentChatService agent) {
        this.service = service;
        this.tasks = tasks;
        this.access = access;
        this.agent = agent;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> create(@PathVariable UUID projectId, @AuthenticationPrincipal UUID actor,
                                 @Valid @RequestBody TaskCreateRequest body, HttpServletRequest request) {
        access.requireProjectMember(projectId, actor);
        UUID agentId = agent.resolveTaskAgent(projectId, actor, body.getAgentId());
        TaskResponse task = service.create(projectId, actor, body);
        agent.startTask(projectId, UUID.fromString(task.getId()), actor, agentId, null);
        return ok(task, request);
    }

    @GetMapping
    public ApiResponse<?> list(@PathVariable UUID projectId, @AuthenticationPrincipal UUID actor,
                               @RequestParam(required = false) UUID groupId, HttpServletRequest request) {
        access.requireProjectMember(projectId, actor);
        return ok(tasks.selectList(Wrappers.<TaskEntity>lambdaQuery()
                .eq(TaskEntity::getProjectId, projectId)
                .eq(groupId != null, TaskEntity::getGroupId, groupId)
                .orderByDesc(TaskEntity::getCreatedAt).last("LIMIT 100")), request);
    }

    @GetMapping("/{taskId}")
    public ApiResponse<?> detail(@PathVariable UUID projectId, @PathVariable UUID taskId,
                                 @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        access.requireProjectMember(projectId, actor);
        TaskEntity task = tasks.selectById(taskId);
        if (task == null || !projectId.equals(task.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在");
        }
        return ok(task, request);
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<?> cancel(@PathVariable UUID projectId, @PathVariable UUID taskId,
                                 @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        return ok(service.cancel(projectId, taskId, actor), request);
    }

    @PostMapping("/{taskId}/start")
    public ApiResponse<?> start(@PathVariable UUID projectId,@PathVariable UUID taskId,@AuthenticationPrincipal UUID actor,
                                @RequestBody(required=false) StartRequest body,HttpServletRequest request) {
        service.require(projectId,taskId,actor);
        UUID agentId = agent.resolveTaskAgent(projectId, actor, body == null ? null : body.agentId());
        agent.startTask(projectId, taskId, actor, agentId, body == null ? null : body.instruction());
        return ok(java.util.Map.of("taskId",taskId,"accepted",true),request);
    }
    public record StartRequest(UUID agentId,String instruction) { }

    private ApiResponse<?> ok(Object value, HttpServletRequest request) {
        return ApiResponse.ok(value, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}

package qg.qgent.task.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import qg.qgent.chat.service.GroupService;
import qg.qgent.common.util.UuidV7;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.task.dto.TaskCreateRequest;
import qg.qgent.task.dto.TaskResponse;
import qg.qgent.task.entity.TaskEntity;
import qg.qgent.task.mapper.TaskMapper;
import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.mapper.WorkspaceMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Task 固定到一个工作区；模型后续操作始终复用该工作区。 */
@Service
@RequiredArgsConstructor
public class TaskService {
    private final TaskMapper tasks;
    private final WorkspaceMapper workspaces;
    private final ProjectMapper projects;
    private final GroupService groups;
    private final ProjectAccessService access;
    private final EventService events;

    @Transactional
    public TaskResponse create(UUID projectId, UUID actor, TaskCreateRequest request) {
        groups.requireGroupMember(projectId,request.getGroupId(),actor);
        projects.selectByIdForUpdate(projectId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(UuidV7.next());
        workspace.setProjectId(projectId);
        workspace.setStorageKey("workspaces/" + workspace.getId());
        workspace.setStatus("PROVISIONING");
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        workspaces.insert(workspace);
        TaskEntity task = new TaskEntity();
        task.setId(UuidV7.next());
        task.setProjectId(projectId);
        task.setGroupId(request.getGroupId());
        task.setTriggerMessageId(request.getTriggerMessageId());
        task.setWorkspaceId(workspace.getId());
        task.setTitle(request.getTitle().trim());
        task.setRequirement(request.getRequirement().trim());
        Long sequence = tasks.selectMaxDisplayCodeSeq(projectId);
        task.setDisplayCode("T-"+(sequence==null?1:sequence+1));
        task.setStatus("PENDING");
        task.setCreatedBy(actor);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        tasks.insert(task);
        publish(task);
        return response(task);
    }

    public TaskEntity require(UUID projectId, UUID taskId, UUID actor) {
        access.requireProjectMember(projectId,actor);
        TaskEntity task = tasks.selectById(taskId);
        if (task == null || !projectId.equals(task.getProjectId())) throw new ApiException(HttpStatus.NOT_FOUND,"TASK_NOT_FOUND","任务不存在");
        groups.requireGroupMember(projectId,task.getGroupId(),actor);
        return task;
    }

    public void updateStatus(UUID taskId, String status) {
        TaskEntity task = tasks.selectById(taskId);
        if (task == null) throw new IllegalArgumentException("任务不存在");
        task.setStatus(status);
        task.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        tasks.updateById(task);
        publish(task);
    }

    @Transactional
    public TaskResponse cancel(UUID projectId, UUID taskId, UUID actor) {
        TaskEntity task = require(projectId,taskId,actor);
        if (!actor.equals(task.getCreatedBy())) access.requireProjectAdmin(projectId,actor);
        if (!List.of("PENDING","RUNNING").contains(task.getStatus())) throw new IllegalArgumentException("当前任务不可取消");
        updateStatus(taskId,"RUNNING".equals(task.getStatus())?"CANCELLING":"CANCELLED");
        return response(tasks.selectById(taskId));
    }

    public TaskResponse response(TaskEntity task) {
        TaskResponse response = new TaskResponse();
        response.setId(task.getId().toString());
        response.setProjectId(task.getProjectId().toString());
        response.setGroupId(task.getGroupId().toString());
        response.setTitle(task.getTitle());
        response.setDisplayCode(task.getDisplayCode());
        response.setRequirement(task.getRequirement());
        response.setStatus(task.getStatus());
        response.setWorkspaceId(task.getWorkspaceId().toString());
        response.setCreatedBy(task.getCreatedBy().toString());
        response.setTriggerMessageId(task.getTriggerMessageId() == null ? null : task.getTriggerMessageId().toString());
        response.setCreatedAt(task.getCreatedAt() == null ? null : task.getCreatedAt().toString());
        response.setUpdatedAt(task.getUpdatedAt() == null ? null : task.getUpdatedAt().toString());
        return response;
    }

    private void publish(TaskEntity task) {
        events.publish(task.getProjectId(),task.getGroupId(),"task.updated",task.getId().toString(),
                Map.of("taskId",task.getId(),"workspaceId",task.getWorkspaceId(),"status",task.getStatus()));
    }
}

package qg.qgent.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import qg.qgent.agent.entity.AgentRunEntity;
import qg.qgent.agent.mapper.AgentRunMapper;
import qg.qgent.chat.service.MessageService;
import qg.qgent.project.service.ProjectAccessService;

import java.util.UUID;

/** 按触发消息停止执行，执行状态保留在内部运行记录中。 */
@Service
@RequiredArgsConstructor
public class AgentRunService {
    private final AgentRunMapper runs;
    private final MessageService messages;
    private final ProjectAccessService access;

    public int cancel(UUID actor, UUID projectId, UUID groupId, UUID messageId) {
        var message = messages.getMessage(actor, projectId, groupId, messageId);
        if (!actor.toString().equals(message.getSenderId())) access.requireProjectAdmin(projectId, actor);
        return runs.update(null, Wrappers.<AgentRunEntity>lambdaUpdate()
                .set(AgentRunEntity::getStatus, "CANCELLING")
                .eq(AgentRunEntity::getProjectId, projectId)
                .eq(AgentRunEntity::getGroupId, groupId)
                .eq(AgentRunEntity::getTriggerMessageId, messageId)
                .isNull(AgentRunEntity::getParentRunId)
                .eq(AgentRunEntity::getStatus, "RUNNING"));
    }
}

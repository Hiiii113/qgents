package qg.qgent.harness.context;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import qg.qgent.chat.service.ChatHistoryService;
import qg.qgent.chat.service.GroupService;
import qg.qgent.memory.service.AgentMemoryService;
import qg.qgent.memory.service.GroupMemoryService;
import qg.qgent.memory.service.ProjectMemoryService;
import qg.qgent.skill.service.SkillService;

/** 将各领域已完成权限过滤的资料组装为 Harness 输入，不实现领域检索或内容管理。 */
@Service
@RequiredArgsConstructor
public class ContextService {
    private final GroupService groups;
    private final ChatHistoryService history;
    private final SkillService skills;
    private final ProjectMemoryService projectMemory;
    private final GroupMemoryService groupMemory;
    private final AgentMemoryService agentMemories;

    public GroupContext buildForGroup(Long actor, Long projectId, Long groupId, Long agentId, Integer limit) {
        var group = groups.requireGroupMember(projectId, groupId, actor);
        return new GroupContext(groupId.toString(), projectId.toString(), group.getName(),
                history.recentMessages(actor, projectId, groupId, limit),
                skills.publishedCatalog(actor, projectId), projectMemory.get(actor, projectId).getContent(),
                groupMemory.read(projectId, groupId).getContent(),
                agentMemories.view(projectId, agentId));
    }
}

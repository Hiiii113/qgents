package qg.qgent.harness.context;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import qg.qgent.memory.dto.ContextMemory;
import qg.qgent.chat.service.ChatHistoryService;
import qg.qgent.chat.service.GroupService;
import qg.qgent.memory.service.MemoryService;
import qg.qgent.skill.service.SkillService;
import java.util.UUID;

/** 将各领域已完成权限过滤的资料组装为 Harness 输入，不实现领域检索或内容管理。 */
@Service
@RequiredArgsConstructor
public class ContextService {
    private final GroupService groups;
    private final ChatHistoryService history;
    private final SkillService skills;
    private final MemoryService memories;

    public GroupContext buildForGroup(UUID actor, UUID projectId, UUID groupId, Integer limit) {
        var group = groups.requireGroupMember(projectId, groupId, actor);
        var approved = memories.list(actor, projectId, "APPROVED", null).stream()
                .map(memory -> new ContextMemory(memory.getTitle(), memory.getContent(), memory.getCategory())).toList();
        return new GroupContext(groupId.toString(), projectId.toString(), group.getName(),
                history.recentMessages(actor, projectId, groupId, limit),
                skills.publishedCatalog(actor, projectId), approved);
    }
}

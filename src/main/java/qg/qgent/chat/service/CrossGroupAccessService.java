package qg.qgent.chat.service;

import org.springframework.stereotype.Service;

import qg.qgent.chat.dto.GroupResponse;
import qg.qgent.common.web.ApiException;

import java.util.Collection;
import java.util.List;

/**
 * 跨群读取与披露的权限边界：发起用户与被调用的 Agent 都必须属于来源群，
 * 且来源群成员包含回答所在群的全部成员时，来源内容才能进入该群。
 */
@Service
public class CrossGroupAccessService {

    private final GroupService groups;

    public CrossGroupAccessService(GroupService groups) {
        this.groups = groups;
    }

    /** 用户与 Agent 都在来源群，且来源群受众覆盖目的群时才允许读取来源群内容。 */
    public boolean readable(Long projectId, Long sourceGroupId, Long destinationGroupId, Long actor, Long agentId) {
        try {
            groups.requireGroupMember(projectId, sourceGroupId, actor);
        } catch (ApiException denied) {
            // 用户不属于来源群、来源群属于其他项目都按不可访问处理，不向调用方区分原因。
            return false;
        }
        return groups.groupAgentIds(sourceGroupId).contains(agentId)
                && groups.audienceCoveredBy(projectId, sourceGroupId, destinationGroupId);
    }

    /** 可供当前运行跨群读取的其他群：本群记忆已在上下文、历史已有当前群检索工具，因此不再列出。 */
    public List<GroupResponse> readableGroups(Long projectId, Long destinationGroupId, Long actor, Long agentId) {
        return groups.list(actor, projectId).stream()
                .filter(group -> !group.getId().equals(destinationGroupId.toString()))
                .filter(group -> readable(projectId, Long.valueOf(group.getId()), destinationGroupId, actor, agentId))
                .toList();
    }

    /** 目的群成员仍被全部来源群覆盖时才能投递回答；来源群或目的群成员变化会在这里被发现。 */
    public boolean deliverable(Long projectId, Long destinationGroupId, Collection<Long> sourceGroupIds) {
        return sourceGroupIds.stream()
                .allMatch(sourceGroupId -> groups.audienceCoveredBy(projectId, sourceGroupId, destinationGroupId));
    }
}

package qg.qgent.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.common.util.UuidV7;
import qg.qgent.team.entity.TeamEntity;
import qg.qgent.team.mapper.TeamMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 团队共享的默认通用 Agent 与 Coding Agent。 */
@Slf4j
@Service
public class DefaultAgentProvisioner {

    private static final Map<String, DefaultSpec> DEFAULT_AGENTS = new LinkedHashMap<>();

    static {
        DEFAULT_AGENTS.put("GENERAL", new DefaultSpec("通用 Agent",
                "回答问题、查询上下文，按需要委托其他 Agent",
                "根据用户问题直接回答或使用工具，需要专业协助时可以委托其他 Agent。"));
        DEFAULT_AGENTS.put("CODING", new DefaultSpec("Coding Agent",
                "阅读、修改代码并按需要验证结果",
                "根据用户需求完成代码工作，先理解现有代码，改动保持简洁，并如实说明验证结果。"));
    }

    private final TeamMapper teamMapper;
    private final AgentMapper agentMapper;

    public DefaultAgentProvisioner(TeamMapper teamMapper, AgentMapper agentMapper) {
        this.teamMapper = teamMapper;
        this.agentMapper = agentMapper;
    }

    @Transactional
    public void ensureForTeam(UUID teamId, UUID ownerUserId) {
        for (Map.Entry<String, DefaultSpec> entry : DEFAULT_AGENTS.entrySet()) {
            ensureRole(teamId, ownerUserId, entry.getKey(), entry.getValue());
        }
    }

    @Transactional
    public void ensureForTeam(UUID teamId) {
        if (teamId == null) {
            return;
        }
        TeamEntity team = teamMapper.selectById(teamId);
        if (team == null) {
            return;
        }
        ensureForTeam(teamId, team.getOwnerUserId());
    }

    private void ensureRole(UUID teamId, UUID ownerUserId, String role, DefaultSpec spec) {
        if (selectDefault(teamId, role) != null) {
            return;
        }
        AgentEntity agent = new AgentEntity();
        agent.setId(UuidV7.next());
        agent.setTeamId(teamId);
        agent.setCreatedBy(ownerUserId);
        agent.setName(spec.name());
        agent.setRole(role);
        agent.setDescription(spec.description());
        agent.setPrompt(spec.prompt());
        agent.setVisibility("TEAM");
        agent.setStatus("ACTIVE");
        agent.setIsDefault(true);
        try {
            agentMapper.insert(agent);
        } catch (DuplicateKeyException e) {
            // 并发兜底：唯一索引 uk_agents_team_default_role 挡住同角色默认 Agent 重复插入
            AgentEntity concurrent = selectDefault(teamId, role);
            if (concurrent == null) {
                throw e;
            }
        }
        log.info("预置默认 Agent：team={} role={} agentId={}", teamId, role, agent.getId());
    }

    private AgentEntity selectDefault(UUID teamId, String role) {
        List<AgentEntity> candidates = agentMapper.selectList(Wrappers.<AgentEntity>lambdaQuery()
                .eq(AgentEntity::getTeamId, teamId)
                .eq(AgentEntity::getRole, role)
                .eq(AgentEntity::getStatus, "ACTIVE")
                .eq(AgentEntity::getIsDefault, true)
                .last("limit 1"));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private record DefaultSpec(String name, String description, String prompt) {
    }
}

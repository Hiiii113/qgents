package qg.qgent.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import qg.qgent.agent.dto.AgentCreateRequest;
import qg.qgent.agent.dto.AgentResponse;
import qg.qgent.agent.dto.AgentUpdateRequest;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.common.util.UuidV7;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.team.entity.TeamEntity;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.team.mapper.TeamMemberMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** 团队 Agent 的可见性、配置与发布审核管理。 */
@Service
@RequiredArgsConstructor
public class AgentService {
    // 保存提示词时拒绝匹配以下凭据和敏感路径
    private static final List<Pattern> SENSITIVE_PROMPT_PATTERNS = List.of(
            Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            Pattern.compile("gh[pous]_[A-Za-z0-9]{20,}"),
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("sk-[A-Za-z0-9]{16,}"),
            Pattern.compile("xox[bap]-[0-9A-Za-z-]{10,}"),
            Pattern.compile("(id_rsa|id_ed25519|/etc/passwd|/home/[A-Za-z0-9_.-]+\\.ssh)"));

    private final AgentMapper agentMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final ProjectAccessService projectAccess;
    private final ProjectMapper projectMapper;
    private final TeamMapper teamMapper;

    public List<AgentResponse> list(UUID actorId, UUID teamId) {
        if (teamMemberMapper.selectByTeamAndUser(teamId, actorId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TEAM_RESOURCE_NOT_FOUND", "团队资源不存在或不可见");
        }
        boolean teamOwner = isTeamOwner(teamId, actorId);
        return agentMapper.selectList(new LambdaQueryWrapper<AgentEntity>()
                        .eq(AgentEntity::getTeamId, teamId)
                        .eq(AgentEntity::getStatus, "ACTIVE")
                        .orderByAsc(AgentEntity::getName))
                .stream()
                .filter(agent -> visibleTo(agent, actorId, teamOwner))
                .map(agent -> toResponse(agent, actorId.equals(agent.getCreatedBy())))
                .toList();
    }

    /** 获取用户可见的 Agent；可选项目 ID 用于校验项目访问权限及团队归属。 */
    public AgentResponse get(UUID teamId, UUID agentId, UUID actor, UUID projectId) {
        if (teamMemberMapper.selectByTeamAndUser(teamId, actor) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TEAM_RESOURCE_NOT_FOUND", "团队资源不存在或不可见");
        }
        if (projectId != null) {
            projectAccess.requireProjectMember(projectId, actor);
            ProjectEntity project = projectMapper.selectById(projectId);
            if (project == null || !teamId.equals(project.getTeamId())) {
                throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见");
            }
        }
        AgentEntity agent = requireAgentInTeam(teamId, agentId);
        boolean owner = actor.equals(agent.getCreatedBy());
        boolean teamOwner = isTeamOwner(teamId, actor);
        if (!visibleTo(agent, actor, teamOwner)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND", "Agent 不存在或不可见");
        }
        return toResponse(agent, owner);
    }

    /** 创建私有、启用的自定义 Agent。 */
    @Transactional
    public AgentResponse create(UUID actor, UUID teamId, AgentCreateRequest request) {
        requireTeamMember(teamId, actor);
        validateRole(request.getRole());
        validateName(request.getName());
        validateAvatar(request.getAvatar());
        validatePrompt(request.getPrompt());
        AgentEntity agent = new AgentEntity();
        agent.setId(UuidV7.next());
        agent.setTeamId(teamId);
        agent.setCreatedBy(actor);
        agent.setName(request.getName().trim());
        agent.setAvatar(blankToNull(request.getAvatar()));
        agent.setRole(request.getRole());
        agent.setDescription(blankToNull(request.getDescription()));
        agent.setPrompt(request.getPrompt());
        agent.setGithubEnabled(request.isGithubEnabled());
        agent.setVisibility("PRIVATE");
        agent.setStatus("ACTIVE");
        agent.setIsDefault(false);
        agentMapper.insert(agent);
        return toResponse(agent, true);
    }

    /** 仅创建者可编辑自定义 Agent；只更新请求中提供的字段。 */
    @Transactional
    public AgentResponse update(UUID actor, UUID teamId, UUID agentId, AgentUpdateRequest request) {
        requireTeamMember(teamId, actor);
        AgentEntity agent = requireAgentInTeam(teamId, agentId);
        requireNotDefault(agent);
        requireEditable(agent, actor);
        if (isEmptyPatch(request)) {
            throw invalidRequest("请求体至少包含一个字段");
        }
        if (request.getName() != null) {
            validateName(request.getName());
            agent.setName(request.getName().trim());
        }
        if (request.getAvatar() != null) {
            validateAvatar(request.getAvatar());
            agent.setAvatar(blankToNull(request.getAvatar()));
        }
        if (request.getRole() != null) {
            validateRole(request.getRole());
            agent.setRole(request.getRole());
        }
        if (request.getDescription() != null) {
            agent.setDescription(blankToNull(request.getDescription()));
        }
        if (request.getGithubEnabled() != null) {
            agent.setGithubEnabled(request.getGithubEnabled());
        }
        if (request.getPrompt() != null) {
            validatePrompt(request.getPrompt());
            agent.setPrompt(request.getPrompt());
        }
        agentMapper.updateById(agent);
        return toResponse(agent, true);
    }

    @Transactional
    public AgentResponse configureGithub(UUID actor, UUID teamId, UUID agentId, boolean enabled) {
        requireTeamMember(teamId, actor);
        AgentEntity agent = requireAgentInTeam(teamId, agentId);
        if (Boolean.TRUE.equals(agent.getIsDefault())) {
            if (!isTeamOwner(teamId, actor)) {
                throw forbidden("仅 Team Owner 可以配置默认 Agent 的能力");
            }
        } else {
            requireEditable(agent, actor);
        }
        agent.setGithubEnabled(enabled);
        agentMapper.updateById(agent);
        return toResponse(agent, true);
    }

    /** 创建者提交发布；团队所有者自建 Agent 直接共享，其他成员进入审核。 */
    @Transactional
    public AgentResponse publish(UUID actor, UUID teamId, UUID agentId) {
        requireTeamMember(teamId, actor);
        AgentEntity agent = requireAgentInTeam(teamId, agentId);
        requireNotDefault(agent);
        requireVisibleForManage(agent, actor);
        if (agent.getCreatedBy() == null || !agent.getCreatedBy().equals(actor)) {
            throw forbidden("仅 Agent 创建者可以提交发布审核");
        }
        if (!"ACTIVE".equals(agent.getStatus()) || !"PRIVATE".equals(agent.getVisibility())) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_STATE_CONFLICT",
                    "只有 PRIVATE 且 ACTIVE 的 Agent 可以发布");
        }
        if (isTeamOwner(teamId, actor)) {
            agent.setVisibility("TEAM");
            agent.setReviewedBy(actor);
            agent.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
            agent.setReviewReason(null);
        } else {
            agent.setVisibility("PENDING");
            agent.setReviewReason(null);
            agent.setReviewedBy(null);
            agent.setReviewedAt(null);
        }
        agentMapper.updateById(agent);
        return toResponse(agent, true);
    }

    /** 团队所有者批准发布，Agent 变为团队共享。 */
    @Transactional
    public AgentResponse approve(UUID actor, UUID teamId, UUID agentId) {
        requireTeamMember(teamId, actor);
        requireTeamOwner(teamId, actor);
        AgentEntity agent = requireAgentInTeam(teamId, agentId);
        requireNotDefault(agent);
        if (!"ACTIVE".equals(agent.getStatus()) || !"PENDING".equals(agent.getVisibility())) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_STATE_CONFLICT",
                    "只有 PENDING（待审核）且 ACTIVE 的 Agent 可以批准发布");
        }
        agent.setVisibility("TEAM");
        agent.setReviewedBy(actor);
        agent.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
        agent.setReviewReason(null);
        agentMapper.updateById(agent);
        return toResponse(agent, false);
    }

    /** 团队所有者拒绝发布，恢复私有并记录拒绝原因。 */
    @Transactional
    public AgentResponse reject(UUID actor, UUID teamId, UUID agentId, String reason) {
        requireTeamMember(teamId, actor);
        requireTeamOwner(teamId, actor);
        AgentEntity agent = requireAgentInTeam(teamId, agentId);
        requireNotDefault(agent);
        if (!"ACTIVE".equals(agent.getStatus()) || !"PENDING".equals(agent.getVisibility())) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_STATE_CONFLICT",
                    "只有 PENDING（待审核）且 ACTIVE 的 Agent 可以拒绝");
        }
        agent.setVisibility("PRIVATE");
        agent.setReviewedBy(actor);
        agent.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
        agent.setReviewReason(blankToNull(reason));
        agentMapper.updateById(agent);
        return toResponse(agent, true);
    }

    /** 创建者或团队所有者归档 Agent；已有运行与历史记录保留。 */
    @Transactional
    public AgentResponse archive(UUID actor, UUID teamId, UUID agentId) {
        requireTeamMember(teamId, actor);
        AgentEntity agent = requireAgentInTeam(teamId, agentId);
        requireNotDefault(agent);
        requireVisibleForManage(agent, actor);
        if (!actor.equals(agent.getCreatedBy()) && !isTeamOwner(teamId, actor)) {
            throw forbidden("仅 Agent 创建者或 Team Owner 可归档");
        }
        if ("ARCHIVED".equals(agent.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_STATE_CONFLICT", "Agent 已归档");
        }
        agent.setStatus("ARCHIVED");
        agentMapper.updateById(agent);
        return toResponse(agent, true);
    }

    public void requireTeamMember(UUID teamId, UUID actor) {
        if (teamMemberMapper.selectByTeamAndUser(teamId, actor) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "团队不存在或不可见");
        }
    }

    /** 共享 Agent 对团队成员可见，私有和待审核 Agent 对创建者可见；所有者另可查看待审核项。 */
    private boolean visibleTo(AgentEntity agent, UUID actor, boolean teamOwner) {
        if ("TEAM".equals(agent.getVisibility())) {
            return true;
        }
        if (agent.getCreatedBy() != null && agent.getCreatedBy().equals(actor)) {
            return true;
        }
        return "PENDING".equals(agent.getVisibility()) && teamOwner;
    }

    /** 以团队记录中的 ownerUserId 判断所有者。 */
    private boolean isTeamOwner(UUID teamId, UUID actor) {
        TeamEntity team = teamMapper.selectById(teamId);
        return team != null && actor.equals(team.getOwnerUserId());
    }

    /** 发布审核仅允许团队所有者执行。 */
    private void requireTeamOwner(UUID teamId, UUID actor) {
        if (!isTeamOwner(teamId, actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TEAM_OWNER_REQUIRED",
                    "仅 Team Owner 可以审核 Agent 发布");
        }
    }

    private AgentEntity requireAgentInTeam(UUID teamId, UUID agentId) {
        AgentEntity agent = agentMapper.selectById(agentId);
        if (agent == null || !teamId.equals(agent.getTeamId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND", "Agent 不存在或不可见");
        }
        return agent;
    }

    private void requireNotDefault(AgentEntity agent) {
        if (Boolean.TRUE.equals(agent.getIsDefault())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AGENT_DEFAULT_IMMUTABLE",
                    "系统预置 Agent 不可编辑、发布或归档");
        }
    }

    /** 私有 Agent 对其他用户返回不存在，其余 Agent 仅创建者可编辑。 */
    private void requireEditable(AgentEntity agent, UUID actor) {
        if (agent.getCreatedBy() != null && agent.getCreatedBy().equals(actor)) {
            return;
        }
        if ("PRIVATE".equals(agent.getVisibility())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND", "Agent 不存在或不可见");
        }
        throw forbidden("仅 Agent 创建者可以编辑");
    }

    /** 管理操作先隐藏他人的私有 Agent，具体操作权限由调用方校验。 */
    private void requireVisibleForManage(AgentEntity agent, UUID actor) {
        if ("PRIVATE".equals(agent.getVisibility())
                && (agent.getCreatedBy() == null || !agent.getCreatedBy().equals(actor))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND", "Agent 不存在或不可见");
        }
    }

    private boolean isEmptyPatch(AgentUpdateRequest request) {
        return request.getGithubEnabled() == null && request.getName() == null
                && request.getAvatar() == null && request.getRole() == null
                && request.getDescription() == null && request.getPrompt() == null;
    }

    private void validateRole(String role) {
        if (role == null || role.isBlank() || role.length() > 32) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AGENT_ROLE_INVALID",
                    "Agent 用途标签不能为空且不能超过 32 个字符");
        }
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw invalidRequest("name 去除首尾空白后非空");
        }
        if (name.trim().length() > 255) {
            throw invalidRequest("name 最长 255 个字符");
        }
    }

    private void validateAvatar(String avatar) {
        if (avatar == null || avatar.isBlank()) {
            return;
        }
        if (avatar.length() > 2048) {
            throw invalidRequest("avatar 最长 2048 个字符");
        }
        try {
            URI uri = new URI(avatar.trim());
            if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw invalidRequest("avatar 必须是合法的 http/https URL");
            }
        } catch (URISyntaxException e) {
            throw invalidRequest("avatar 必须是合法的 http/https URL");
        }
    }

    private void validatePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw invalidRequest("prompt 不能为空");
        }
        if (prompt.length() > 20000) {
            throw invalidRequest("prompt 最长 20000 个字符");
        }
        for (Pattern pattern : SENSITIVE_PROMPT_PATTERNS) {
            if (pattern.matcher(prompt).find()) {
                throw invalidRequest("prompt 不得包含凭据、Token、私钥或宿主机敏感信息");
            }
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ApiException invalidRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "AGENT_INVALID_REQUEST", message);
    }

    private ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "AGENT_FORBIDDEN", message);
    }

    private AgentResponse toResponse(AgentEntity agent, boolean owner) {
        AgentResponse response = new AgentResponse(agent.getId().toString(), agent.getName(), agent.getAvatar(),
                agent.getRole(), agent.getDescription(), owner ? agent.getPrompt() : null, agent.getVisibility(),
                agent.getStatus(), agent.getCreatedBy() == null ? null : agent.getCreatedBy().toString(),
                Boolean.TRUE.equals(agent.getIsDefault()));
        // 审核字段：拒绝原因仅创建者可见；审核人/时间团队内可见（Team Owner 审核需要看到历史）
        response.setReviewReason(owner ? agent.getReviewReason() : null);
        response.setGithubEnabled(Boolean.TRUE.equals(agent.getGithubEnabled()));
        response.setReviewedBy(agent.getReviewedBy() == null ? null : agent.getReviewedBy().toString());
        response.setReviewedAt(agent.getReviewedAt() == null ? null
                : agent.getReviewedAt().toInstant(ZoneOffset.UTC).toString());
        return response;
    }

}

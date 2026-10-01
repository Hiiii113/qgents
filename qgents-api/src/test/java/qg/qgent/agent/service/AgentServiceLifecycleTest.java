package qg.qgent.agent.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import qg.qgent.agent.dto.AgentCreateRequest;
import qg.qgent.agent.dto.AgentResponse;
import qg.qgent.agent.dto.AgentUpdateRequest;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.agent.mapper.AgentRunMapper;
import qg.qgent.chat.mapper.GroupAgentMapper;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.team.entity.TeamEntity;
import qg.qgent.team.entity.TeamMemberEntity;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.team.mapper.TeamMemberMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 自定义 Agent 生命周期管理接口（契约 §11.1 接口补充 v2.0.3 §2-§6）授权与状态转换规则测试。 */
class AgentServiceLifecycleTest {
    private final AgentMapper agents = mock(AgentMapper.class);
    private final TeamMemberMapper teamMembers = mock(TeamMemberMapper.class);
    private final ProjectAccessService projectAccess = mock(ProjectAccessService.class);
    private final GroupAgentMapper groupAgents = mock(GroupAgentMapper.class);
    private final ChatGroupMapper chatGroups = mock(ChatGroupMapper.class);
    private final AgentRunMapper agentRuns = mock(AgentRunMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final TeamMapper teams = mock(TeamMapper.class);
    private final AgentService service = new AgentService(agents, teamMembers, projectAccess, groupAgents,
            chatGroups, agentRuns, projects, teams);

    private final UUID teamId = UUID.randomUUID();
    private final UUID creator = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    @Test
    void teamOwnerCanEnableAndDisableDefaultAgentGithubCapability() {
        member(teamId, owner);
        TeamEntity team = new TeamEntity();
        team.setOwnerUserId(owner);
        when(teams.selectById(teamId)).thenReturn(team);
        UUID id = agentId();
        AgentEntity agent = agent(id, teamId, owner, "TEAM", "ACTIVE", true);
        when(agents.selectById(id)).thenReturn(agent);
        assertEquals("default", service.configureGithub(owner, teamId, id, "default").getGithubCredentialRef());
        assertNull(service.configureGithub(owner, teamId, id, "").getGithubCredentialRef());
        member(teamId, other);
        assertThrows(ApiException.class, () -> service.configureGithub(other, teamId, id, "default"));
    }

    @Test
    void createPersistsPrivateActiveNonDefaultAgent() {
        member(teamId, creator);
        AgentCreateRequest request = createRequest("Java 后端 Agent", "DEVELOPER", "负责后端实现",
                "遵循项目 API 规范。", "https://cdn.example.com/a.png");

        AgentResponse response = service.create(creator, teamId, request);

        assertEquals("PRIVATE", response.getVisibility());
        assertEquals("ACTIVE", response.getStatus());
        assertFalse(response.getIsDefault());
        assertEquals(creator.toString(), response.getCreatedBy());
        assertEquals("Java 后端 Agent", response.getName());
        assertEquals("DEVELOPER", response.getRole());
        assertEquals("遵循项目 API 规范。", response.getPrompt());
        verify(agents).insert(any(AgentEntity.class));
    }

    @Test
    void createRejectsNonMember() {
        when(teamMembers.selectByTeamAndUser(teamId, creator)).thenReturn(null);

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(creator, teamId, createRequest("a", "DEVELOPER", null, "p", null)));

        assertEquals(HttpStatus.NOT_FOUND, error.status());
        assertEquals("TEAM_NOT_FOUND", error.code());
        verify(agents, never()).insert(any(AgentEntity.class));
    }

    @Test
    void createRejectsBlankRole() {
        member(teamId, creator);

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(creator, teamId, createRequest("a", " ", null, "p", null)));

        assertEquals("AGENT_ROLE_INVALID", error.code());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.status());
        verify(agents, never()).insert(any(AgentEntity.class));
    }

    @Test
    void createRejectsBlankName() {
        member(teamId, creator);

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(creator, teamId, createRequest("   ", "DEVELOPER", null, "p", null)));

        assertEquals("AGENT_INVALID_REQUEST", error.code());
        verify(agents, never()).insert(any(AgentEntity.class));
    }

    @Test
    void createRejectsInvalidAvatarUrl() {
        member(teamId, creator);

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(creator, teamId,
                        createRequest("a", "DEVELOPER", null, "p", "not-a-url")));

        assertEquals("AGENT_INVALID_REQUEST", error.code());
        verify(agents, never()).insert(any(AgentEntity.class));
    }

    @Test
    void createRejectsSensitivePrompt() {
        member(teamId, creator);

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(creator, teamId,
                        createRequest("a", "DEVELOPER", null, "ssh-rsa ghp_AbCdEf1234567890abcdefghijkl", null)));

        assertEquals("AGENT_INVALID_REQUEST", error.code());
        verify(agents, never()).insert(any(AgentEntity.class));
    }

    @Test
    void updateAppliesProvidedFields() {
        member(teamId, creator);
        AgentEntity agent = agent(agentId(), teamId, creator, "PRIVATE", "ACTIVE", false);
        when(agents.selectById(agent.getId())).thenReturn(agent);
        AgentUpdateRequest request = new AgentUpdateRequest();
        request.setName("新名字");
        request.setRole("REVIEWER");
        request.setDescription("");

        AgentResponse response = service.update(creator, teamId, agent.getId(), request);

        assertEquals("新名字", response.getName());
        assertEquals("REVIEWER", response.getRole());
        assertNull(response.getDescription());
        verify(agents).updateById(any(AgentEntity.class));
    }

    @Test
    void updateRejectsNonMember() {
        when(teamMembers.selectByTeamAndUser(teamId, creator)).thenReturn(null);

        ApiException error = assertThrows(ApiException.class,
                () -> service.update(creator, teamId, agentId(), new AgentUpdateRequest()));

        assertEquals("TEAM_NOT_FOUND", error.code());
    }

    @Test
    void updateRejectsDefaultAgent() {
        member(teamId, creator);
        AgentEntity agent = agent(agentId(), teamId, creator, "TEAM", "ACTIVE", true);
        when(agents.selectById(agent.getId())).thenReturn(agent);

        ApiException error = assertThrows(ApiException.class,
                () -> service.update(creator, teamId, agent.getId(), patch("name", "x")));

        assertEquals("AGENT_DEFAULT_IMMUTABLE", error.code());
        verify(agents, never()).updateById(any(AgentEntity.class));
    }

    @Test
    void updateRejectsOtherUsersPrivateAgentAsNotFound() {
        member(teamId, other);
        AgentEntity agent = agent(agentId(), teamId, creator, "PRIVATE", "ACTIVE", false);
        when(agents.selectById(agent.getId())).thenReturn(agent);

        ApiException error = assertThrows(ApiException.class,
                () -> service.update(other, teamId, agent.getId(), patch("name", "x")));

        assertEquals("AGENT_NOT_FOUND", error.code());
    }

    @Test
    void updateRejectsOtherUsersTeamAgentAsForbidden() {
        member(teamId, other);
        AgentEntity agent = agent(agentId(), teamId, creator, "TEAM", "ACTIVE", false);
        when(agents.selectById(agent.getId())).thenReturn(agent);

        ApiException error = assertThrows(ApiException.class,
                () -> service.update(other, teamId, agent.getId(), patch("name", "x")));

        assertEquals("AGENT_FORBIDDEN", error.code());
    }

    @Test
    void updateRejectsEmptyPatch() {
        member(teamId, creator);
        AgentEntity agent = agent(agentId(), teamId, creator, "PRIVATE", "ACTIVE", false);
        when(agents.selectById(agent.getId())).thenReturn(agent);

        ApiException error = assertThrows(ApiException.class,
                () -> service.update(creator, teamId, agent.getId(), new AgentUpdateRequest()));

        assertEquals("AGENT_INVALID_REQUEST", error.code());
        verify(agents, never()).updateById(any(AgentEntity.class));
    }

    @Test
    void updateRejectsBlankRoleValue() {
        member(teamId, creator);
        AgentEntity agent = agent(agentId(), teamId, creator, "PRIVATE", "ACTIVE", false);
        when(agents.selectById(agent.getId())).thenReturn(agent);

        ApiException error = assertThrows(ApiException.class,
                () -> service.update(creator, teamId, agent.getId(), patch("role", " ")));

        assertEquals("AGENT_ROLE_INVALID", error.code());
    }

    @Test
    void publishSubmitsForReviewNotDirectlyTeam() {
        member(teamId, creator);
        AgentEntity agent = agent(agentId(), teamId, creator, "PRIVATE", "ACTIVE", false);
        when(agents.selectById(agent.getId())).thenReturn(agent);

        AgentResponse response = service.publish(creator, teamId, agent.getId());

        // v2.0.6 审核化：发布只到 PENDING（待审核），不再直接 TEAM
        assertEquals("PENDING", response.getVisibility());
        assertEquals("ACTIVE", response.getStatus());
        verify(agents).updateById(any(AgentEntity.class));
    }

    @Test
    void publishByTeamOwnerPublishesImmediatelyWithoutApproval() {
        member(teamId, owner);
        TeamEntity team = team(teamId, owner);
        when(teams.selectById(teamId)).thenReturn(team);
        AgentEntity agent = agent(agentId(), teamId, owner, "PRIVATE", "ACTIVE", false);
        when(agents.selectById(agent.getId())).thenReturn(agent);

        AgentResponse response = service.publish(owner, teamId, agent.getId());

        assertEquals("TEAM", response.getVisibility());
        assertEquals(owner.toString(), response.getReviewedBy());
        assertNotNull(response.getReviewedAt());
        verify(agents).updateById(any(AgentEntity.class));
    }

    @Test
    void publishRejectsOtherUsersPrivateAgentAsNotFound() {
        member(teamId, other);
        AgentEntity agent = agent(agentId(), teamId, creator, "PRIVATE", "ACTIVE", false);
        when(agents.selectById(agent.getId())).thenReturn(agent);

        ApiException error = assertThrows(ApiException.class,
                () -> service.publish(other, teamId, agent.getId()));

        // 他人 PRIVATE Agent 对非创建者不可见 → 404（与 get/list 一致）
        assertEquals("AGENT_NOT_FOUND", error.code());
        verify(agents, never()).updateById(any(AgentEntity.class));
    }

    @Test
    void publishRejectsArchivedAgent() {
        member(teamId, creator);
        AgentEntity agent = agent(agentId(), teamId, creator, "PRIVATE", "ARCHIVED", false);
        when(agents.selectById(agent.getId())).thenReturn(agent);

        ApiException error = assertThrows(ApiException.class,
                () -> service.publish(creator, teamId, agent.getId()));

        assertEquals("AGENT_STATE_CONFLICT", error.code());
        verify(agents, never()).updateById(any(AgentEntity.class));
    }

    @Test
    void publishRejectsAlreadyPendingOrTeamAgent() {
        member(teamId, creator);
        AgentEntity pending = agent(agentId(), teamId, creator, "PENDING", "ACTIVE", false);
        when(agents.selectById(pending.getId())).thenReturn(pending);

        ApiException error = assertThrows(ApiException.class,
                () -> service.publish(creator, teamId, pending.getId()));

        assertEquals("AGENT_STATE_CONFLICT", error.code());
    }

    @Test
    void approvePromotesPendingToTeamForOwner() {
        member(teamId, owner);
        TeamEntity team = team(teamId, owner);
        when(teams.selectById(teamId)).thenReturn(team);
        AgentEntity agent = agent(agentId(), teamId, creator, "PENDING", "ACTIVE", false);
        when(agents.selectById(agent.getId())).thenReturn(agent);

        AgentResponse response = service.approve(owner, teamId, agent.getId());

        assertEquals("TEAM", response.getVisibility());
        assertEquals(owner.toString(), response.getReviewedBy());
        verify(agents).updateById(any(AgentEntity.class));
    }

    @Test
    void approveRejectsNonOwner() {
        member(teamId, other);
        TeamEntity team = team(teamId, owner);
        when(teams.selectById(teamId)).thenReturn(team);
        AgentEntity agent = agent(agentId(), teamId, creator, "PENDING", "ACTIVE", false);
        when(agents.selectById(agent.getId())).thenReturn(agent);

        ApiException error = assertThrows(ApiException.class,
                () -> service.approve(other, teamId, agent.getId()));

        assertEquals("TEAM_OWNER_REQUIRED", error.code());
        verify(agents, never()).updateById(any(AgentEntity.class));
    }

    @Test
    void rejectReturnsPendingToPrivateWithReason() {
        member(teamId, owner);
        TeamEntity team = team(teamId, owner);
        when(teams.selectById(teamId)).thenReturn(team);
        AgentEntity agent = agent(agentId(), teamId, creator, "PENDING", "ACTIVE", false);
        when(agents.selectById(agent.getId())).thenReturn(agent);

        AgentResponse response = service.reject(owner, teamId, agent.getId(), "prompt 包含敏感信息");

        assertEquals("PRIVATE", response.getVisibility());
        assertEquals("prompt 包含敏感信息", response.getReviewReason());
        verify(agents).updateById(any(AgentEntity.class));
    }

    @Test
    void archiveArchivesActiveAgent() {
        member(teamId, creator);
        AgentEntity agent = agent(agentId(), teamId, creator, "TEAM", "ACTIVE", false);
        when(agents.selectById(agent.getId())).thenReturn(agent);

        AgentResponse response = service.archive(creator, teamId, agent.getId());

        assertEquals("ARCHIVED", response.getStatus());
        verify(agents).updateById(any(AgentEntity.class));
    }

    @Test
    void archiveAllowsTeamOwner() {
        member(teamId, owner);
        TeamEntity team = team(teamId, owner);
        when(teams.selectById(teamId)).thenReturn(team);
        AgentEntity agent = agent(agentId(), teamId, creator, "TEAM", "ACTIVE", false);
        when(agents.selectById(agent.getId())).thenReturn(agent);

        AgentResponse response = service.archive(owner, teamId, agent.getId());

        assertEquals("ARCHIVED", response.getStatus());
    }

    @Test
    void archiveRejectsOtherUsersPrivateAgentAsNotFound() {
        member(teamId, owner);
        TeamEntity team = team(teamId, owner);
        when(teams.selectById(teamId)).thenReturn(team);
        AgentEntity agent = agent(agentId(), teamId, creator, "PRIVATE", "ACTIVE", false);
        when(agents.selectById(agent.getId())).thenReturn(agent);

        ApiException error = assertThrows(ApiException.class,
                () -> service.archive(owner, teamId, agent.getId()));

        assertEquals("AGENT_NOT_FOUND", error.code());
        verify(agents, never()).updateById(any(AgentEntity.class));
    }

    @Test
    void archiveRejectsAlreadyArchivedAgent() {
        member(teamId, creator);
        AgentEntity agent = agent(agentId(), teamId, creator, "TEAM", "ARCHIVED", false);
        when(agents.selectById(agent.getId())).thenReturn(agent);

        ApiException error = assertThrows(ApiException.class,
                () -> service.archive(creator, teamId, agent.getId()));

        assertEquals("AGENT_STATE_CONFLICT", error.code());
    }

    @Test
    void rejectArchivedAgentCannotBePublished() {
        member(teamId, creator);
        AgentEntity agent = agent(agentId(), teamId, creator, "TEAM", "ARCHIVED", false);
        when(agents.selectById(agent.getId())).thenReturn(agent);

        ApiException error = assertThrows(ApiException.class,
                () -> service.publish(creator, teamId, agent.getId()));

        assertEquals("AGENT_STATE_CONFLICT", error.code());
        verify(agents, never()).updateById(any(AgentEntity.class));
    }

    private void member(UUID team, UUID user) {
        TeamMemberEntity member = new TeamMemberEntity();
        member.setTeamId(team);
        member.setUserId(user);
        member.setRole("TEAM_MEMBER");
        when(teamMembers.selectByTeamAndUser(team, user)).thenReturn(member);
    }

    private TeamEntity team(UUID team, UUID ownerUser) {
        TeamEntity entity = new TeamEntity();
        entity.setId(team);
        entity.setOwnerUserId(ownerUser);
        entity.setStatus("ACTIVE");
        return entity;
    }

    private AgentEntity agent(UUID id, UUID team, UUID createdBy, String visibility, String status,
                              boolean isDefault) {
        AgentEntity agent = new AgentEntity();
        agent.setId(id);
        agent.setTeamId(team);
        agent.setCreatedBy(createdBy);
        agent.setName("dev-agent");
        agent.setRole("DEVELOPER");
        agent.setVisibility(visibility);
        agent.setStatus(status);
        agent.setIsDefault(isDefault);
        return agent;
    }

    private UUID agentId() {
        return UUID.randomUUID();
    }

    private AgentCreateRequest createRequest(String name, String role, String description, String prompt,
                                             String avatar) {
        AgentCreateRequest request = new AgentCreateRequest();
        request.setName(name);
        request.setRole(role);
        request.setDescription(description);
        request.setPrompt(prompt);
        request.setAvatar(avatar);
        return request;
    }

    private AgentUpdateRequest patch(String field, String value) {
        AgentUpdateRequest request = new AgentUpdateRequest();
        switch (field) {
            case "name" -> request.setName(value);
            case "role" -> request.setRole(value);
            case "description" -> request.setDescription(value);
            case "prompt" -> request.setPrompt(value);
            case "avatar" -> request.setAvatar(value);
            default -> throw new IllegalArgumentException(field);
        }
        return request;
    }
}

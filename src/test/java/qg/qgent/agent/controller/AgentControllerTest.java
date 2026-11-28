package qg.qgent.agent.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.agent.service.AgentApplicationService;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.support.TestIds;

/** 项目管理员通过 REST 停用与重新启用 Agent。 */
class AgentControllerTest {

    private final Long actor = TestIds.next(), project = TestIds.next(), agentId = TestIds.next();
    private final AgentMapper agents = mock(AgentMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final AgentApplicationService service =
            new AgentApplicationService(agents, access, mock(ProjectMapper.class), 10);
    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new AgentController(service))
                    .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                    .build();

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void projectAdminDisablesAnActiveAgentAndEnablesItAgain() throws Exception {
        AgentEntity agent = agent(AgentEntity.STATUS_ACTIVE);
        when(agents.selectById(agentId)).thenAnswer(call -> agent);
        when(agents.disable(agentId))
                .thenAnswer(
                        call -> {
                            agent.setStatus(AgentEntity.STATUS_DISABLED);
                            return 1;
                        });
        when(agents.enable(agentId))
                .thenAnswer(
                        call -> {
                            agent.setStatus(AgentEntity.STATUS_ACTIVE);
                            return 1;
                        });

        mvc.perform(post("/api/v1/projects/{projectId}/agents/{agentId}/disable", project, agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
        mvc.perform(post("/api/v1/projects/{projectId}/agents/{agentId}/enable", project, agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        verify(access, times(2)).requireProjectAdmin(project, actor);
    }

    private AgentEntity agent(String status) {
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setProjectId(project);
        agent.setName("评审助手");
        agent.setDefaultAgent(false);
        agent.setStatus(status);
        return agent;
    }
}

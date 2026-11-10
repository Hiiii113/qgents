package qg.qgent.agent.service;

import qg.qgent.support.TestIds;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.common.web.ApiException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 每个项目拥有自己的默认 Agent；Agent 只属于创建它的项目。 */
class AgentServiceTest {
    private final AgentMapper agents = mock(AgentMapper.class);
    private final AgentService service = new AgentService(agents);

    @Test
    void everyProjectGetsItsOwnDefaultAgent() {
        when(agents.insert(any(AgentEntity.class))).thenAnswer(call -> {
            call.getArgument(0, AgentEntity.class).setId(TestIds.next());
            return 1;
        });
        Long firstProject = TestIds.next(), secondProject = TestIds.next();

        AgentEntity first = service.createDefaultAgent(firstProject);
        AgentEntity second = service.createDefaultAgent(secondProject);

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getProjectId()).isEqualTo(firstProject);
        assertThat(second.getProjectId()).isEqualTo(secondProject);
        assertThat(first.getName()).isEqualTo(AgentService.DEFAULT_AGENT_NAME);
        assertThat(first.getDefaultAgent()).isTrue();
    }

    @Test
    void defaultAgentOfAnotherProjectIsNotAcceptedAsProjectAgent() {
        Long ownProject = TestIds.next(), otherProject = TestIds.next(), agentId = TestIds.next();
        AgentEntity foreign = new AgentEntity();
        foreign.setId(agentId);
        foreign.setProjectId(otherProject);
        when(agents.selectBatchIds(List.of(agentId))).thenReturn(List.of(foreign));

        assertThatThrownBy(() -> service.requireProjectAgents(ownProject, List.of(agentId)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_GROUP_AGENT"));
    }

    @Test
    void unknownAgentIsRejectedBeforeInvitesAreWritten() {
        when(agents.selectBatchIds(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.requireProjectAgents(TestIds.next(), List.of(TestIds.next())))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_GROUP_AGENT"));
    }

    @Test
    void projectsDefaultAgentIsLookedUpByProject() {
        Long projectId = TestIds.next();
        AgentEntity agent = new AgentEntity();
        agent.setId(TestIds.next());
        agent.setProjectId(projectId);
        when(agents.selectDefaultAgent(projectId)).thenReturn(agent);

        assertThat(service.defaultAgent(projectId)).isSameAs(agent);
    }

    @Test
    void missingDefaultAgentIsReportedInsteadOfReturningNull() {
        Long projectId = TestIds.next();
        when(agents.selectDefaultAgent(projectId)).thenReturn(null);

        assertThatThrownBy(() -> service.defaultAgent(projectId)).isInstanceOf(IllegalStateException.class);
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(agents).selectDefaultAgent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(projectId);
        verify(agents, never()).insert(any(AgentEntity.class));
    }
}

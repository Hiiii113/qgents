package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import qg.qgent.entity.AgentEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.TeamMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DefaultAgentProvisionerTest {
    @Test
    void provisionsOnlyGeneralAndCodingAgents() {
        AgentMapper agents = mock(AgentMapper.class);
        when(agents.selectList(any())).thenReturn(List.of());
        new DefaultAgentProvisioner(mock(TeamMapper.class), agents)
                .ensureForTeam(UUID.randomUUID(), UUID.randomUUID());
        ArgumentCaptor<AgentEntity> inserted = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agents, times(2)).insert(inserted.capture());
        assertThat(inserted.getAllValues()).extracting(AgentEntity::getRole).containsExactly("GENERAL", "CODING");
    }

    @Test
    void existingDefaultsAreNotInsertedAgain() {
        AgentMapper agents = mock(AgentMapper.class);
        when(agents.selectList(any())).thenReturn(List.of(new AgentEntity()));
        new DefaultAgentProvisioner(mock(TeamMapper.class), agents)
                .ensureForTeam(UUID.randomUUID(), UUID.randomUUID());
        verify(agents, never()).insert(any(AgentEntity.class));
    }
}

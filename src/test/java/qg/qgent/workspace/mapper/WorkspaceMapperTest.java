package qg.qgent.workspace.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.Test;
import qg.qgent.common.persistence.typehandler.UuidBinaryTypeHandler;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceMapperTest {
    @Test
    void leaseUsesRootExecutionOwnershipAndOnlyTerminalRunsAllowTakeover() {
        var configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidBinaryTypeHandler.class);
        configuration.addMapper(WorkspaceMapper.class);
        var parameters = Map.of("projectId", UUID.randomUUID(), "workspaceId", UUID.randomUUID(),
                "agentRunId", UUID.randomUUID(), "token", "lease-token");
        String namespace = WorkspaceMapper.class.getName();
        var claim = configuration.getMappedStatement(namespace + ".claimWriteLease").getBoundSql(parameters);
        assertThat(claim.getSql()).contains("write_lease_agent_run_id=?", "from agent_runs owner_run",
                        "owner_run.id=write_lease_agent_run_id", "('SUCCEEDED','FAILED','CANCELLED')")
                .doesNotContain("tasks", "CANCELLING");
        for (String method : new String[]{"claimWriteLease", "renewWriteLease", "releaseWriteLease"}) {
            var sql = configuration.getMappedStatement(namespace + "." + method).getBoundSql(parameters);
            assertThat(sql.getParameterMappings()).anyMatch(field -> "agentRunId".equals(field.getProperty()))
                    .anyMatch(field -> "token".equals(field.getProperty()));
        }
    }
}

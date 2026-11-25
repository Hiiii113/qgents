package qg.qgent.workspace.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import qg.qgent.support.MapperTestSupport;
import qg.qgent.support.TestIds;

class WorkspaceMapperTest {
    @Test
    void leaseUsesRootExecutionOwnershipAndOnlyTerminalRunsAllowTakeover() {
        var configuration = MapperTestSupport.configuration(WorkspaceMapper.class);
        var parameters =
                Map.of(
                        "projectId",
                        TestIds.next(),
                        "workspaceId",
                        TestIds.next(),
                        "agentRunId",
                        TestIds.next(),
                        "token",
                        "lease-token");
        String namespace = WorkspaceMapper.class.getName();
        var claim =
                configuration
                        .getMappedStatement(namespace + ".claimWriteLease")
                        .getBoundSql(parameters);
        assertThat(claim.getSql())
                .contains(
                        "write_lease_agent_run_id = ?",
                        "FROM agent_runs owner_run",
                        "owner_run.id = write_lease_agent_run_id",
                        "('SUCCEEDED', 'FAILED', 'CANCELLED')")
                .doesNotContain("tasks", "CANCELLING");
        for (String method :
                new String[] {"claimWriteLease", "renewWriteLease", "releaseWriteLease"}) {
            var sql =
                    configuration
                            .getMappedStatement(namespace + "." + method)
                            .getBoundSql(parameters);
            assertThat(sql.getParameterMappings())
                    .anyMatch(field -> "agentRunId".equals(field.getProperty()))
                    .anyMatch(field -> "token".equals(field.getProperty()));
        }
    }
}

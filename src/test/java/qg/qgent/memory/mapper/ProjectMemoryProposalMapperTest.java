package qg.qgent.memory.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import qg.qgent.support.TestIds;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目记忆候选 SQL 在真实 MyBatis 参数绑定下执行，只把 JDBC 连接换成替身：
 * 参数名写错的语句会在这里抛 BindingException，而不是等到生产审批才失败。
 */
class ProjectMemoryProposalMapperTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private MybatisConfiguration configuration;
    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(connection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(statement);
        when(statement.execute()).thenReturn(true);
        when(statement.getUpdateCount()).thenReturn(1);
        configuration = new MybatisConfiguration();
        configuration.addMapper(ProjectMemoryProposalMapper.class);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void markAppliedTransitionsOnlyPendingCandidatesAndRecordsTheApproval() throws Exception {
        Long id = TestIds.next(), approvedBy = TestIds.next(), approvalMessage = TestIds.next();

        try (SqlSession session = factory.openSession()) {
            assertThat(session.getMapper(ProjectMemoryProposalMapper.class)
                    .markApplied(id, approvedBy, approvalMessage, "v2")).isEqualTo(1);
        }

        assertThat(updateSql()).contains("status = 'PENDING'", "applied_version = ?", "approved_message_id = ?")
                .contains("approved_at = UTC_TIMESTAMP(6)");
    }

    @Test
    void bindMessageUpdatesOnlyTheRunStillAwaitingApproval() throws Exception {
        Long runId = TestIds.next(), messageId = TestIds.next();

        try (SqlSession session = factory.openSession()) {
            assertThat(session.getMapper(ProjectMemoryProposalMapper.class).bindMessage(runId, messageId)).isEqualTo(1);
        }

        assertThat(updateSql()).contains("message_id = ?", "agent_run_id = ?", "status = 'PENDING'");
    }

    @Test
    void pendingLookupReturnsBaselineVersionsOfCandidatesStillAwaitingApproval() {
        String sql = statement("selectPendingBaseVersions").getBoundSql(Map.of("projectId", TestIds.next()))
                .getSql();

        assertThat(sql).contains("base_version", "project_id = ?", "status = 'PENDING'");
    }

    @Test
    void everyStatementParameterResolvesToADeclaredMethodParameter() {
        assertParametersResolve("selectByMessageId", Map.of("messageId", TestIds.next()));
        assertParametersResolve("selectPendingBaseVersions", Map.of("projectId", TestIds.next()));
        assertParametersResolve("markApplied", Map.of("id", TestIds.next(), "approvedBy", TestIds.next(),
                "approvalMessageId", TestIds.next(), "version", "v2"));
        assertParametersResolve("bindMessage", Map.of("agentRunId", TestIds.next(), "messageId", TestIds.next()));
    }

    private void assertParametersResolve(String method, Map<String, Object> declaredParameters) {
        var boundSql = statement(method).getBoundSql(declaredParameters);
        assertThat(boundSql.getParameterMappings()).isNotEmpty().allSatisfy(mapping ->
                assertThat(declaredParameters).as("参数 %s 必须对应方法上的 @Param", mapping.getProperty())
                        .containsKey(mapping.getProperty()));
    }

    private org.apache.ibatis.mapping.MappedStatement statement(String method) {
        return configuration.getMappedStatement(ProjectMemoryProposalMapper.class.getName() + "." + method);
    }

    private String updateSql() throws Exception {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        return sql.getValue();
    }
}

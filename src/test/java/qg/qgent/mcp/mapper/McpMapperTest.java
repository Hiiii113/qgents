package qg.qgent.mcp.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Optional;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import qg.qgent.mcp.entity.McpCredential;
import qg.qgent.mcp.entity.McpServer;
import qg.qgent.mcp.entity.McpServer.AuthMode;
import qg.qgent.mcp.entity.McpServer.Scope;
import qg.qgent.support.MapperTestSupport;

/**
 * MCP 的 record 结果映射在真实 MyBatis 结果集处理下构造对象，列名或构造函数顺序写错会在这里失败。
 */
class McpMapperTest {

    @Test
    void constructsServerRecordFromQueryColumns() throws Exception {
        var resultSet = resultSet(
            "id", "project_id", "name", "endpoint", "scope", "auth_mode", "enabled", "oauth_client");
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getLong("id")).thenReturn(7L);
        when(resultSet.getLong("project_id")).thenReturn(11L);
        when(resultSet.getString("name")).thenReturn("Docs");
        when(resultSet.getString("endpoint")).thenReturn("https://example.com/mcp");
        when(resultSet.getString("scope")).thenReturn("PERSONAL");
        when(resultSet.getString("auth_mode")).thenReturn("OAUTH");
        when(resultSet.getBoolean("enabled")).thenReturn(true);
        when(resultSet.getString("oauth_client")).thenReturn("{\"clientId\":\"x\"}");

        try (SqlSession session = factory(resultSet).openSession()) {
            Optional<McpServer> server = session.getMapper(McpMapper.class).server(11L, 7L, false);

            assertThat(server)
                .contains(new McpServer(7L, 11L, "Docs", "https://example.com/mcp",
                    Scope.PERSONAL, AuthMode.OAUTH, true, "{\"clientId\":\"x\"}"));
        }
    }

    @Test
    void constructsCredentialRecordWithUtcExpiry() throws Exception {
        var resultSet = resultSet(
            "id", "owner_user_id", "access_token", "refresh_token",
            "expires_at", "reauth_required", "revision");
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getLong("id")).thenReturn(21L);
        when(resultSet.getLong("owner_user_id")).thenReturn(100L);
        when(resultSet.getString("access_token")).thenReturn("access");
        when(resultSet.getString("refresh_token")).thenReturn("refresh");
        when(resultSet.getObject("expires_at", LocalDateTime.class))
            .thenReturn(LocalDateTime.of(2030, 1, 1, 0, 0));
        when(resultSet.getBoolean("reauth_required")).thenReturn(false);
        when(resultSet.getLong("revision")).thenReturn(3L);

        try (SqlSession session = factory(resultSet).openSession()) {
            McpCredential credential =
                session.getMapper(McpMapper.class).lockCredential(21L).orElseThrow();

            assertThat(credential)
                .isEqualTo(new McpCredential(21L, 100L, "access", "refresh",
                    Instant.parse("2030-01-01T00:00:00Z"), false, 3L));
        }
    }

    @Test
    void serverInsertUsesGeneratedKeysForTheDraftId() {
        var configuration = MapperTestSupport.configuration(McpMapper.class);
        var statement =
            configuration.getMappedStatement(McpMapper.class.getName() + ".insertServer");

        assertThat(statement.getKeyProperties()).containsExactly("id");
        assertThat(statement.getKeyGenerator())
            .isInstanceOf(org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator.class);
        assertThat(statement.getBoundSql(new McpMapper.McpServerDraft(
            11L, "Docs", "https://example.com/mcp", "SHARED", "BEARER")).getSql())
            .contains("INSERT INTO mcp_servers", "VALUES (?, ?, ?, ?, ?)");
    }

    @Test
    void createReturnsTheGeneratedServerId() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet keys = mock(ResultSet.class);
        ResultSetMetaData keyMetadata = mock(ResultSetMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString(), eq(PreparedStatement.RETURN_GENERATED_KEYS)))
            .thenReturn(statement);
        when(statement.execute()).thenReturn(false);
        when(statement.getUpdateCount()).thenReturn(1);
        when(statement.getGeneratedKeys()).thenReturn(keys);
        when(keys.getMetaData()).thenReturn(keyMetadata);
        when(keyMetadata.getColumnCount()).thenReturn(1);
        when(keys.next()).thenReturn(true, false);
        when(keys.getLong(1)).thenReturn(55L);
        var configuration = MapperTestSupport.configuration(McpMapper.class);
        configuration.setEnvironment(
            new Environment("test", new JdbcTransactionFactory(), dataSource));
        var factory = new MybatisSqlSessionFactoryBuilder().build(configuration);

        try (SqlSession session = factory.openSession()) {
            long id = session.getMapper(McpMapper.class).create(
                11L, "Docs", "https://example.com/mcp", Scope.SHARED, AuthMode.BEARER);

            assertThat(id).isEqualTo(55L);
        }
    }

    @Test
    void clearingCredentialTokensBindsExplicitJdbcTypes() {
        var configuration = MapperTestSupport.configuration(McpMapper.class);
        var parameters = new HashMap<String, Object>();
        parameters.put("id", 21L);
        parameters.put("access", null);
        parameters.put("refresh", null);
        parameters.put("expiry", null);
        parameters.put("replace", true);

        var mappings =
            configuration
                .getMappedStatement(McpMapper.class.getName() + ".saveCredential")
                .getBoundSql(parameters)
                .getParameterMappings();

        assertThat(mappings)
            .anySatisfy(mapping -> {
                assertThat(mapping.getProperty()).isEqualTo("access");
                assertThat(mapping.getJdbcType()).isEqualTo(org.apache.ibatis.type.JdbcType.LONGVARCHAR);
            })
            .anySatisfy(mapping -> {
                assertThat(mapping.getProperty()).isEqualTo("refresh");
                assertThat(mapping.getJdbcType()).isEqualTo(org.apache.ibatis.type.JdbcType.LONGVARCHAR);
            })
            .anySatisfy(mapping -> {
                assertThat(mapping.getProperty()).isEqualTo("expiry");
                assertThat(mapping.getJdbcType()).isEqualTo(org.apache.ibatis.type.JdbcType.TIMESTAMP);
            });
    }

    private SqlSessionFactory factory(ResultSet resultSet) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.execute()).thenReturn(true);
        when(statement.getResultSet()).thenReturn(resultSet);
        // MyBatis 在取到第一个结果集前会反复读取 update count；默认值 0 会造成死循环。
        when(statement.getUpdateCount()).thenReturn(-1);
        when(statement.executeQuery()).thenReturn(resultSet);
        var configuration = MapperTestSupport.configuration(McpMapper.class);
        configuration.setEnvironment(
            new Environment("test", new JdbcTransactionFactory(), dataSource));
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private ResultSet resultSet(String... columns) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(columns.length);
        for (int index = 0; index < columns.length; index++) {
            when(metadata.getColumnName(index + 1)).thenReturn(columns[index]);
            when(metadata.getColumnLabel(index + 1)).thenReturn(columns[index]);
        }
        return resultSet;
    }
}

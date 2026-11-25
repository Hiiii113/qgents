package qg.qgent.mcp.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import qg.qgent.mcp.entity.McpCredential;
import qg.qgent.mcp.entity.McpServer;
import qg.qgent.mcp.entity.McpServer.AuthMode;
import qg.qgent.mcp.entity.McpServer.Scope;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * MCP 连接、凭证与 Agent 分配的数据访问。
 * <p>
 * {@link McpServer} / {@link McpCredential} 是不可变的 record，列映射在 XML 的
 * {@code resultMap} 构造函数中显式声明；其余 SQL 需要 {@code FOR UPDATE}、
 * {@code ON DUPLICATE KEY} 或原子条件更新，统一放在 {@code mapper/McpMapper.xml}。
 */
@Mapper
public interface McpMapper {

    List<McpServer> list(@Param("projectId") long projectId);

    Optional<McpServer> server(
        @Param("projectId") long projectId,
        @Param("id") long id,
        @Param("lock") boolean lock);

    /**
     * 新建连接并返回数据库生成的自增 ID。MyBatis 的生成键需要可写的参数对象，
     * 因此用内部草稿对象承接 {@code useGeneratedKeys} 的结果。
     */
    default long create(long projectId, String name, String endpoint, Scope scope, AuthMode authMode) {
        var draft = new McpServerDraft(projectId, name, endpoint, scope.name(), authMode.name());
        insertServer(draft);
        return draft.getId();
    }

    int insertServer(McpServerDraft draft);

    int update(@Param("id") long id, @Param("name") String name, @Param("enabled") boolean enabled);

    int delete(@Param("id") long id);

    int saveOAuthClient(@Param("id") long id, @Param("client") String client);

    Optional<McpCredential> credential(
        @Param("serverId") long serverId,
        @Param("owner") Long owner,
        @Param("lock") boolean lock);

    Optional<McpCredential> lockCredential(@Param("id") long id);

    /**
     * 确保凭证行存在（共享连接占 0 槽位，个人连接占用户槽位），再在事务内加锁读取。
     */
    default McpCredential ensureCredential(long serverId, Long owner) {
        insertCredential(serverId, owner);
        return credential(serverId, owner, true).orElseThrow();
    }

    int insertCredential(@Param("serverId") long serverId, @Param("owner") Long owner);

    int saveCredential(
        @Param("id") long id,
        @Param("access") String access,
        @Param("refresh") String refresh,
        @Param("expiry") Instant expiry,
        @Param("replace") boolean replace);

    int requireReauthorization(@Param("id") long id);

    List<Long> agentIds(@Param("credentialId") long credentialId);

    /**
     * 替换凭证的 Agent 分配：先清空再批量写入，调用方已在事务内。
     */
    default void replaceAgents(long credentialId, List<Long> agentIds) {
        deleteAgentAssignments(credentialId);
        if (!agentIds.isEmpty()) insertAgentAssignments(credentialId, agentIds);
    }

    int deleteAgentAssignments(@Param("credentialId") long credentialId);

    int insertAgentAssignments(
        @Param("credentialId") long credentialId, @Param("agentIds") List<Long> agentIds);

    List<McpServer> assignedServers(
        @Param("projectId") long projectId,
        @Param("agentId") long agentId,
        @Param("actor") long actor);

    Optional<McpCredential> assignedCredential(
        @Param("serverId") long serverId,
        @Param("owner") Long owner,
        @Param("agentId") long agentId);

    /**
     * 新建连接时由 XML 的 {@code useGeneratedKeys} 回填 ID。
     */
    class McpServerDraft {
        private Long id;
        private final long projectId;
        private final String name;
        private final String endpoint;
        private final String scope;
        private final String authMode;

        McpServerDraft(long projectId, String name, String endpoint, String scope, String authMode) {
            this.projectId = projectId;
            this.name = name;
            this.endpoint = endpoint;
            this.scope = scope;
            this.authMode = authMode;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public long getProjectId() {
            return projectId;
        }

        public String getName() {
            return name;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public String getScope() {
            return scope;
        }

        public String getAuthMode() {
            return authMode;
        }
    }
}

package qg.qgent.mcp.mapper;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import qg.qgent.mcp.entity.McpCredential;
import qg.qgent.mcp.entity.McpServer;
import qg.qgent.mcp.entity.McpServer.AuthMode;
import qg.qgent.mcp.entity.McpServer.Scope;

import javax.sql.DataSource;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class McpMapper {
    private static final RowMapper<McpCredential> CREDENTIAL_ROW = (rs, row) -> {
        LocalDateTime expires = rs.getObject("expires_at", LocalDateTime.class);
        return new McpCredential(rs.getLong("id"), rs.getObject("owner_user_id", Long.class),
            rs.getString("access_token"), rs.getString("refresh_token"),
            expires == null ? null : expires.toInstant(ZoneOffset.UTC),
            rs.getBoolean("reauth_required"), rs.getLong("revision"));
    };
    private final JdbcClient db;

    public McpMapper(DataSource dataSource) {
        this.db = JdbcClient.create(dataSource);
    }

    public List<McpServer> list(long projectId) {
        return db.sql("SELECT * FROM mcp_servers WHERE project_id = :project ORDER BY id")
            .param("project", projectId).query(McpServer.class).list();
    }

    public Optional<McpServer> server(long projectId, long id, boolean lock) {
        return db.sql("SELECT * FROM mcp_servers WHERE project_id = :project AND id = :id" + (lock ? " FOR UPDATE" : ""))
            .param("project", projectId).param("id", id).query(McpServer.class).optional();
    }

    public long create(long projectId, String name, String endpoint, Scope scope, AuthMode authMode) {
        var key = new GeneratedKeyHolder();
        db.sql("""
            INSERT INTO mcp_servers (project_id, name, endpoint, scope, auth_mode)
            VALUES (:project, :name, :endpoint, :scope, :auth)
            """).param("project", projectId).param("name", name).param("endpoint", endpoint)
            .param("scope", scope.name()).param("auth", authMode.name()).update(key, "id");
        return key.getKey().longValue();
    }

    public void update(long id, String name, boolean enabled) {
        db.sql("UPDATE mcp_servers SET name = :name, enabled = :enabled WHERE id = :id")
            .param("name", name).param("enabled", enabled).param("id", id).update();
    }

    public void delete(long id) {
        db.sql("DELETE FROM mcp_servers WHERE id = :id").param("id", id).update();
    }

    public void saveOAuthClient(long id, String client) {
        db.sql("UPDATE mcp_servers SET oauth_client = :client WHERE id = :id")
            .param("client", client).param("id", id).update();
    }

    public Optional<McpCredential> credential(long serverId, Long owner, boolean lock) {
        return db.sql("SELECT * FROM mcp_credentials WHERE server_id = :server AND owner_slot = :owner" + (lock ? " FOR UPDATE" : ""))
            .param("server", serverId).param("owner", owner == null ? 0L : owner)
            .query(CREDENTIAL_ROW).optional();
    }

    public Optional<McpCredential> lockCredential(long id) {
        return db.sql("SELECT * FROM mcp_credentials WHERE id = :id FOR UPDATE")
            .param("id", id).query(CREDENTIAL_ROW).optional();
    }

    public McpCredential ensureCredential(long serverId, Long owner) {
        db.sql("""
            INSERT INTO mcp_credentials (server_id, owner_user_id) VALUES (:server, :owner)
            ON DUPLICATE KEY UPDATE server_id = mcp_credentials.server_id
            """).param("server", serverId).param("owner", owner, Types.BIGINT).update();
        return credential(serverId, owner, true).orElseThrow();
    }

    public void saveCredential(long id, String access, String refresh, Instant expiry, boolean replace) {
        db.sql("""
            UPDATE mcp_credentials SET access_token = :access, refresh_token = :refresh,
              expires_at = :expiry, reauth_required = FALSE, revision = revision + :increment WHERE id = :id
            """).param("access", access, Types.LONGVARCHAR).param("refresh", refresh, Types.LONGVARCHAR)
            .param("expiry", expiry == null ? null : LocalDateTime.ofInstant(expiry, ZoneOffset.UTC), Types.TIMESTAMP)
            .param("increment", replace ? 1 : 0).param("id", id).update();
    }

    public void requireReauthorization(long id) {
        db.sql("""
            UPDATE mcp_credentials SET access_token = NULL, refresh_token = NULL,
              expires_at = NULL, reauth_required = TRUE, revision = revision + 1 WHERE id = :id
            """).param("id", id).update();
    }

    public List<Long> agentIds(long credentialId) {
        return db.sql("SELECT agent_id FROM mcp_agent_assignments WHERE credential_id = :id ORDER BY agent_id")
            .param("id", credentialId).query(Long.class).list();
    }

    public void replaceAgents(long credentialId, List<Long> agentIds) {
        db.sql("DELETE FROM mcp_agent_assignments WHERE credential_id = :id")
            .param("id", credentialId).update();
        for (Long agentId : agentIds) {
            db.sql("INSERT INTO mcp_agent_assignments (credential_id, agent_id) VALUES (:id, :agent)")
                .param("id", credentialId).param("agent", agentId).update();
        }
    }

    public List<McpServer> assignedServers(long projectId, long agentId, long actor) {
        return db.sql("""
            SELECT s.* FROM mcp_servers s
            JOIN mcp_credentials c ON c.server_id = s.id
              AND c.owner_slot = CASE WHEN s.scope = 'PERSONAL' THEN :actor ELSE 0 END
            JOIN mcp_agent_assignments a ON a.credential_id = c.id AND a.agent_id = :agent
            WHERE s.project_id = :project AND s.enabled ORDER BY s.id
            """).param("project", projectId).param("agent", agentId).param("actor", actor)
            .query(McpServer.class).list();
    }

    public Optional<McpCredential> assignedCredential(long serverId, Long owner, long agentId) {
        return db.sql("""
            SELECT c.* FROM mcp_credentials c
            JOIN mcp_agent_assignments a ON a.credential_id = c.id AND a.agent_id = :agent
            WHERE c.server_id = :server AND c.owner_slot = :owner
            """).param("server", serverId).param("owner", owner == null ? 0L : owner).param("agent", agentId)
            .query(CREDENTIAL_ROW).optional();
    }
}

package qg.qgent.user.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.Test;
import qg.qgent.common.persistence.typehandler.UuidBinaryTypeHandler;
import java.util.HashMap;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class UserMapperTest {
    @Test
    void lastTeamUsesBinaryUuidAndCanBeCleared() {
        var configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidBinaryTypeHandler.class);
        configuration.addMapper(UserMapper.class);
        var parameters = new HashMap<String, Object>();
        parameters.put("userId", UUID.randomUUID());
        parameters.put("teamId", null);
        var sql = configuration.getMappedStatement(UserMapper.class.getName() + ".updateLastTeam")
                .getBoundSql(parameters);
        assertThat(sql.getSql()).contains("SET last_team_id = ? WHERE id = ?");
        var mappings = configuration.getMappedStatement(UserMapper.class.getName() + ".selectByIdForUpdate")
                .getResultMaps().getFirst().getResultMappings();
        assertThat(mappings).anyMatch(field -> "lastTeamId".equals(field.getProperty())
                && field.getTypeHandler() instanceof UuidBinaryTypeHandler);
    }
}

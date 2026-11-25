package qg.qgent.user.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import org.junit.jupiter.api.Test;
import qg.qgent.support.MapperTestSupport;
import qg.qgent.support.TestIds;
import qg.qgent.user.entity.UserEntity;

class UserMapperTest {
    @Test
    void lastTeamUsesBinaryUuidAndCanBeCleared() {
        var configuration = MapperTestSupport.configuration(UserMapper.class);
        var parameters = new HashMap<String, Object>();
        parameters.put("userId", TestIds.next());
        parameters.put("teamId", null);
        var sql =
                configuration
                        .getMappedStatement(UserMapper.class.getName() + ".updateLastTeam")
                        .getBoundSql(parameters);
        assertThat(sql.getSql()).contains("SET last_team_id = ? WHERE id = ?");
        var locking =
                configuration.getMappedStatement(UserMapper.class.getName() + ".selectByIdForUpdate");
        assertThat(locking.getBoundSql(java.util.Map.of("userId", TestIds.next())).getSql())
                .contains("last_team_id", "FOR UPDATE");
        assertThat(locking.getResultMaps().getFirst().getType()).isEqualTo(UserEntity.class);
        assertThat(configuration.isMapUnderscoreToCamelCase()).isTrue();
    }
}

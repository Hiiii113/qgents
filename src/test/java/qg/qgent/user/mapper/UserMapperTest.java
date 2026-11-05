package qg.qgent.user.mapper;

import qg.qgent.support.TestIds;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import static org.assertj.core.api.Assertions.assertThat;

class UserMapperTest {
    @Test
    void lastTeamUsesBinaryUuidAndCanBeCleared() {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(UserMapper.class);
        var parameters = new HashMap<String, Object>();
        parameters.put("userId", TestIds.next());
        parameters.put("teamId", null);
        var sql = configuration.getMappedStatement(UserMapper.class.getName() + ".updateLastTeam")
                .getBoundSql(parameters);
        assertThat(sql.getSql()).contains("SET last_team_id = ? WHERE id = ?");
        var mappings = configuration.getMappedStatement(UserMapper.class.getName() + ".selectByIdForUpdate")
                .getResultMaps().getFirst().getResultMappings();
        assertThat(mappings).anyMatch(field -> "lastTeamId".equals(field.getProperty()));
    }
}

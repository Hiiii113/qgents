package qg.qgent.team.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.Test;
import qg.qgent.common.persistence.typehandler.UuidBinaryTypeHandler;
import qg.qgent.team.entity.TeamEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TeamMapperTest {
    @Test
    void clearingAvatarIncludesNullInDatabaseUpdate() {
        var configuration = configuration();
        TeamEntity team = new TeamEntity();
        team.setId(UUID.randomUUID());
        team.setName("团队");
        var sql = configuration.getMappedStatement(TeamMapper.class.getName() + ".updateById")
                .getBoundSql(Map.of("et", team));
        assertThat(sql.getSql()).contains("avatar_url=?");
        assertThat(sql.getParameterMappings()).anyMatch(field -> "et.avatarUrl".equals(field.getProperty()));
    }

    @Test
    void teamListsAndMemberQueriesKeepUuidAndProfileMappings() {
        var configuration = configuration();
        configuration.addMapper(TeamMemberMapper.class);
        for (String method : new String[]{"selectMembershipPage", "selectMembershipByActivity"}) {
            var mappings = configuration.getMappedStatement(TeamMapper.class.getName() + "." + method)
                    .getResultMaps().getFirst().getResultMappings();
            assertThat(mappings).anyMatch(field -> "id".equals(field.getProperty())
                    && field.getTypeHandler() instanceof UuidBinaryTypeHandler);
            assertThat(mappings).anyMatch(field -> "avatarUrl".equals(field.getProperty()));
            assertThat(mappings).anyMatch(field -> "lastActivityAt".equals(field.getProperty()));
        }
        for (String method : new String[]{"selectByTeamAndUser", "selectByTeamId", "selectByUserId"}) {
            var mappings = configuration.getMappedStatement(TeamMemberMapper.class.getName() + "." + method)
                    .getResultMaps().getFirst().getResultMappings();
            assertThat(mappings).anyMatch(field -> "userId".equals(field.getProperty())
                    && field.getTypeHandler() instanceof UuidBinaryTypeHandler);
        }
    }

    private MybatisConfiguration configuration() {
        var configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidBinaryTypeHandler.class);
        configuration.addMapper(TeamMapper.class);
        return configuration;
    }
}

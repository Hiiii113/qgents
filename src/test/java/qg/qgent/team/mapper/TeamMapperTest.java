package qg.qgent.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import qg.qgent.support.MapperTestSupport;
import qg.qgent.support.TestIds;
import qg.qgent.team.entity.TeamEntity;
import qg.qgent.team.entity.TeamMemberEntity;
import qg.qgent.team.mapper.view.TeamMemberView;
import qg.qgent.team.mapper.view.TeamMembershipView;

class TeamMapperTest {
    @Test
    void clearingAvatarIncludesNullInDatabaseUpdate() {
        var configuration = MapperTestSupport.configuration(TeamMapper.class);
        TeamEntity team = new TeamEntity();
        team.setId(TestIds.next());
        team.setName("团队");
        var sql =
                configuration
                        .getMappedStatement(TeamMapper.class.getName() + ".updateById")
                        .getBoundSql(Map.of("et", team));
        assertThat(sql.getSql()).contains("avatar_url=?");
        assertThat(sql.getParameterMappings())
                .anyMatch(field -> "et.avatarUrl".equals(field.getProperty()));
    }

    @Test
    void teamListsAndMemberQueriesKeepUuidAndProfileMappings() {
        var configuration = MapperTestSupport.configuration(TeamMapper.class, TeamMemberMapper.class);
        assertThat(configuration.isMapUnderscoreToCamelCase()).isTrue();

        var membership =
                configuration.getMappedStatement(TeamMapper.class.getName() + ".selectMembershipPage");
        assertThat(
                        membership
                                .getBoundSql(
                                        Map.of(
                                                "userId",
                                                TestIds.next(),
                                                "anchor",
                                                0L,
                                                "limit",
                                                20))
                                .getSql())
                .contains("member_count", "t.avatar_url", "t.owner_user_id");
        assertThat(membership.getResultMaps().getFirst().getType())
                .isEqualTo(TeamMembershipView.class);

        var memberPage =
                configuration.getMappedStatement(TeamMemberMapper.class.getName() + ".selectMemberPage");
        assertThat(
                        memberPage
                                .getBoundSql(
                                        Map.of(
                                                "teamId",
                                                TestIds.next(),
                                                "anchor",
                                                0L,
                                                "limit",
                                                20))
                                .getSql())
                .contains("tm.user_id", "u.display_name", "u.avatar_url", "u.email");
        assertThat(memberPage.getResultMaps().getFirst().getType()).isEqualTo(TeamMemberView.class);
    }

    @Test
    void teamMemberInsertComesFromBaseMapper() {
        var configuration = MapperTestSupport.configuration(TeamMemberMapper.class);
        TeamMemberEntity member = new TeamMemberEntity();
        member.setTeamId(TestIds.next());
        member.setUserId(TestIds.next());
        member.setRole("TEAM_MEMBER");

        var sql =
                configuration
                        .getMappedStatement(TeamMemberMapper.class.getName() + ".insert")
                        .getBoundSql(member);

        assertThat(sql.getSql())
                .contains("INSERT INTO team_members")
                .contains("team_id", "user_id", "role");
        assertThat(sql.getParameterMappings())
                .anyMatch(parameter -> "teamId".equals(parameter.getProperty()))
                .anyMatch(parameter -> "userId".equals(parameter.getProperty()));
    }
}

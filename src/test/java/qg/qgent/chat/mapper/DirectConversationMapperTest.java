package qg.qgent.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qg.qgent.support.MapperTestSupport;
import qg.qgent.support.TestIds;

/**
 * 私聊参与者的两条 SQL：按用户列出自己参与的会话，以及 Agent 只能写自己是 B 方的会话。
 */
class DirectConversationMapperTest {

    private MybatisConfiguration configuration;

    @BeforeEach
    void setUp() {
        configuration = MapperTestSupport.configuration(DirectConversationMapper.class);
    }

    @Test
    void aUserOnlyListsConversationsWhereTheyAreOneOfTheTwoParticipants() {
        String sql = sql("listForUser", Map.of("projectId", TestIds.next(), "userId", TestIds.next()));

        assertThat(sql)
                .contains("project_id", "participant_a_id", "participant_b_id")
                .contains("participant_a_type = 'USER'", "participant_b_type = 'USER'");
    }

    @Test
    void onlyTheAgentPeerOfAConversationMayWriteIntoIt() {
        String sql =
                sql(
                        "countAgentPeer",
                        Map.of("groupId", TestIds.next(), "agentId", TestIds.next()));

        assertThat(sql)
                .contains("group_id = ?", "participant_b_type = 'AGENT'", "participant_b_id = ?");
    }

    private String sql(String method, Map<String, Object> parameters) {
        var statement = configuration.getMappedStatement(
                DirectConversationMapper.class.getName() + "." + method);
        assertThat(statement.getBoundSql(parameters).getParameterMappings())
                .isNotEmpty()
                .allSatisfy(
                        mapping ->
                                assertThat(parameters)
                                        .as("参数 %s 必须对应方法上的 @Param", mapping.getProperty())
                                        .containsKey(mapping.getProperty()));
        return statement.getBoundSql(parameters).getSql();
    }
}

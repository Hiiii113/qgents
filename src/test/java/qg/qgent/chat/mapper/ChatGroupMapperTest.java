package qg.qgent.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qg.qgent.support.MapperTestSupport;
import qg.qgent.support.TestIds;

/**
 * 群列表 SQL 只取群聊行，私聊会话不会出现在群列表里。
 */
class ChatGroupMapperTest {

    private MybatisConfiguration configuration;

    @BeforeEach
    void setUp() {
        configuration = MapperTestSupport.configuration(ChatGroupMapper.class);
    }

    @Test
    void projectGroupListExcludesDirectConversations() {
        assertThat(sql("listByProject", Map.of("projectId", TestIds.next())))
                .contains("project_id = ?", "kind = 'GROUP'");
    }

    private String sql(String method, Map<String, Object> parameters) {
        return configuration
                .getMappedStatement(ChatGroupMapper.class.getName() + "." + method)
                .getBoundSql(parameters)
                .getSql();
    }
}

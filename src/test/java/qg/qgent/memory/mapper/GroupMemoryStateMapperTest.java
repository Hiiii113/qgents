package qg.qgent.memory.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.Test;
import qg.qgent.support.TestIds;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 群记忆提炼状态的 SQL 约束：失败不改水位、领取必须与并发任务互斥。 */
class GroupMemoryStateMapperTest {

    private final MybatisConfiguration configuration = new MybatisConfiguration();

    GroupMemoryStateMapperTest() {
        configuration.addMapper(GroupMemoryStateMapper.class);
    }

    @Test
    void failureKeepsProcessedSequenceUnchanged() {
        String sql = sql("recordFailure", Map.of("groupId", TestIds.next(), "error", "超时",
                "nextAttemptAt", LocalDateTime.now(), "now", LocalDateTime.now()));

        assertThat(sql).doesNotContain("last_processed_sequence");
    }

    @Test
    void claimOnlyTakesIdleOrStaleRunningRows() {
        String sql = sql("claim", Map.of("groupId", TestIds.next(), "now", LocalDateTime.now(),
                "staleBefore", LocalDateTime.now()));

        assertThat(sql).contains("status = 'IDLE'").contains("updated_at < ");
    }

    @Test
    void successAdvancesSequenceAndClearsCorrection() {
        String sql = sql("finishSuccess", Map.of("groupId", TestIds.next(), "processedThrough", 7L,
                "now", LocalDateTime.now()));

        assertThat(sql).contains("last_processed_sequence = ").contains("correction = NULL");
    }

    private String sql(String method, Map<String, Object> parameters) {
        return configuration.getMappedStatement(GroupMemoryStateMapper.class.getName() + "." + method)
                .getBoundSql(parameters).getSql();
    }
}

package qg.qgent.memory.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.Test;
import qg.qgent.support.TestIds;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 群记忆提炼状态的 SQL 约束：失败不改水位、完成需持有领取、纠错累积。 */
class GroupMemoryStateMapperTest {

    private final MybatisConfiguration configuration = new MybatisConfiguration();

    GroupMemoryStateMapperTest() {
        configuration.addMapper(GroupMemoryStateMapper.class);
    }

    @Test
    void failureKeepsProcessedSequenceAndCorrection() {
        String sql = sql("recordFailure", Map.of("groupId", TestIds.next(), "claimToken", LocalDateTime.now(),
                "error", "超时", "nextAttemptAt", LocalDateTime.now(), "now", LocalDateTime.now()));

        assertThat(sql).doesNotContain("last_processed_sequence").doesNotContain("correction =");
    }

    @Test
    void claimTakesIdleOrStaleRowsAndWritesClaimToken() {
        String sql = sql("claim", Map.of("groupId", TestIds.next(), "now", LocalDateTime.now(),
                "staleBefore", LocalDateTime.now()));

        assertThat(sql).contains("status = 'IDLE'").contains("updated_at < ").contains("claim_token = ");
    }

    @Test
    void resultUpdatesRequireTheClaimTokenStillBeingOwned() {
        Map<String, Object> success = Map.of("groupId", TestIds.next(), "claimToken", LocalDateTime.now(),
                "processedThrough", 7L, "now", LocalDateTime.now());
        String finish = sql("finishSuccess", success);
        String failure = sql("recordFailure", Map.of("groupId", TestIds.next(), "claimToken", LocalDateTime.now(),
                "error", "超时", "nextAttemptAt", LocalDateTime.now(), "now", LocalDateTime.now()));

        assertThat(finish).contains("status = 'RUNNING'", "claim_token = ?");
        assertThat(failure).contains("status = 'RUNNING'", "claim_token = ?");
    }

    @Test
    void successNeverMovesTheWatermarkBackwards() {
        String sql = sql("finishSuccess", Map.of("groupId", TestIds.next(), "claimToken", LocalDateTime.now(),
                "processedThrough", 7L, "now", LocalDateTime.now()));

        assertThat(sql).contains("GREATEST(last_processed_sequence").contains("correction = NULL");
    }

    @Test
    void correctionsAccumulateInsteadOfOverwritingEachOther() {
        String sql = sql("requestCorrection", Map.of("projectId", TestIds.next(), "groupId", TestIds.next(),
                "correction", "结论应为 B", "at", LocalDateTime.now()));

        assertThat(sql).contains("CONCAT(correction", "CHAR(10)");
    }

    @Test
    void candidateScanIsBoundedAndOrderedByActivity() {
        String sql = sql("selectCandidates", Map.of("staleBefore", LocalDateTime.now(), "limit", 20));

        assertThat(sql).contains("ORDER BY last_message_at").contains("LIMIT ?");
    }

    private String sql(String method, Map<String, Object> parameters) {
        return configuration.getMappedStatement(GroupMemoryStateMapper.class.getName() + "." + method)
                .getBoundSql(parameters).getSql();
    }
}

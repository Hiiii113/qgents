package qg.qgent.memory.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.Test;
import qg.qgent.support.TestIds;

import java.time.LocalDateTime;
import java.util.HashMap;
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
        String finish = sql("finishSuccess", successParameters());
        String failure = sql("recordFailure", Map.of("groupId", TestIds.next(), "claimToken", LocalDateTime.now(),
                "error", "超时", "nextAttemptAt", LocalDateTime.now(), "now", LocalDateTime.now()));

        assertThat(finish).contains("status = 'RUNNING'", "claim_token = ?");
        assertThat(failure).contains("status = 'RUNNING'", "claim_token = ?");
    }

    @Test
    void successNeverMovesTheWatermarkBackwards() {
        String sql = sql("finishSuccess", successParameters());

        assertThat(sql).contains("GREATEST(last_processed_sequence");
    }

    @Test
    void successOnlyClearsTheCorrectionItActuallyApplied() {
        String sql = sql("finishSuccess", successParameters());

        assertThat(sql).contains("CASE WHEN correction = ").contains("ELSE correction END");
    }

    @Test
    void correctionsAccumulateInsteadOfOverwritingEachOther() {
        String sql = sql("requestCorrection", Map.of("projectId", TestIds.next(), "groupId", TestIds.next(),
                "correction", "结论应为 B", "at", LocalDateTime.now()));

        assertThat(sql).contains("CONCAT(correction", "CHAR(10)");
    }

    @Test
    void candidateScanPagesByGroupIdCursorSoLaterGroupsAreReached() {
        String sql = sql("selectCandidates", Map.of("staleBefore", LocalDateTime.now(),
                "afterGroupId", TestIds.next(), "limit", 20));

        assertThat(sql).contains("group_id > ?").contains("ORDER BY group_id");
    }

    @Test
    void firstCandidatePageHasNoCursorCondition() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("staleBefore", LocalDateTime.now());
        parameters.put("afterGroupId", null);
        parameters.put("limit", 20);

        assertThat(sql("selectCandidates", parameters)).doesNotContain("group_id >");
    }

    private Map<String, Object> successParameters() {
        return Map.of("groupId", TestIds.next(), "claimToken", LocalDateTime.now(),
                "appliedCorrection", "结论应为 B", "processedThrough", 7L, "now", LocalDateTime.now());
    }

    private String sql(String method, Map<String, Object> parameters) {
        return configuration.getMappedStatement(GroupMemoryStateMapper.class.getName() + "." + method)
                .getBoundSql(parameters).getSql();
    }
}

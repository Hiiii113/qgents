package qg.qgent.memory.service;

import java.time.LocalDateTime;

/**
 * 群记忆正文、版本号和最后修改时间的一次一致读取。
 * <p>
 * 后台提炼拿 revision 做乐观锁：写回时版本已变说明 Agent 在此期间改过正文，本次结果作废。
 */
public record GroupMemorySnapshot(String content, long revision, LocalDateTime updatedAt) {
}

package qg.qgent.memory.service;

import java.time.Instant;

/**
 * 群记忆的调度元数据，与 memory.md 同目录存放在 memory-meta.json，只由程序读写。
 * <p>
 * revision 是乐观锁版本号：每次写入 memory.md 自增。后台提炼只有读到相同版本才写回，
 * 提炼期间 Agent 改过正文时放弃旧结果，不会覆盖新改动。
 */
public record GroupMemoryMeta(
    long revision,
    long lastProcessedSequence,
    String discoveredVersion,
    Instant lastDiscoveredAt) {

    /**
     * 文件不存在时的默认值：没有任何写入，也没有提炼过任何消息。
     */
    public static GroupMemoryMeta initial() {
        return new GroupMemoryMeta(0, 0, null, null);
    }

    /**
     * 写入正文后的新元数据：revision 自增，提炼水位只增不减。
     */
    public GroupMemoryMeta written(long processedThrough) {
        return new GroupMemoryMeta(
            revision + 1,
            Math.max(lastProcessedSequence, processedThrough),
            discoveredVersion,
            lastDiscoveredAt);
    }

    /**
     * Agent 改写过正文但不需要推进提炼水位时的元数据。
     */
    public GroupMemoryMeta rewritten() {
        return written(lastProcessedSequence);
    }

    /**
     * 只推进项目记忆发现读过哪一版群记忆，不影响正文版本和提炼水位。
     */
    public GroupMemoryMeta discovered(String version, Instant at) {
        return new GroupMemoryMeta(revision, lastProcessedSequence, version, at);
    }
}

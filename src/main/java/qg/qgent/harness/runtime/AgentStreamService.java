package qg.qgent.harness.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import qg.qgent.chat.service.GroupService;
import qg.qgent.harness.dto.AgentRunSnapshot;
import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.harness.entity.AgentRunToolEntity;
import qg.qgent.harness.entity.RunStatus;
import qg.qgent.harness.mapper.AgentRunMapper;
import qg.qgent.harness.mapper.AgentRunToolMapper;
import qg.qgent.realtime.service.RealtimeHub;
import qg.qgent.realtime.websocket.RealtimeFrame;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 一次 Agent 运行的可展示进度。
 * <p>
 * 正文增量只保存在当前进程，按 120ms/200 字聚合后经 WebSocket 推送给群成员，不写数据库；
 * 工具明细写入 {@code agent_run_tools}，快照以数据库为准，运行结束后或进程重启后仍能回看。
 * 正文重启后不可恢复，以数据库终态、工具明细和最终消息为准。
 */
@Service
public class AgentStreamService {
    private static final Logger log = LoggerFactory.getLogger(AgentStreamService.class);

    // 正文增量聚合：最多 120ms 或 200 字发一帧，避免逐 token 推送。
    private static final long FLUSH_INTERVAL_MS = 120;
    private static final int FLUSH_CHARS = 200;
    // 群成员推送目标缓存：每条增量都查库会成为热点，短暂缓存足以覆盖成员变动。
    private static final long MEMBER_CACHE_MS = 10_000;
    private static final int TOOL_NAME_LIMIT = 64;
    // 工具入参与结果只保留前 2000 字，截断时末尾加省略号。
    private static final int TOOL_TEXT_LIMIT = 2000;

    private final AgentRunMapper runs;
    private final AgentRunToolMapper toolRuns;
    private final GroupService groups;
    private final RealtimeHub realtime;
    private final Map<Long, LiveRun> live = new ConcurrentHashMap<>();
    // 根运行生命周期内的 seq/round 计数器：子运行结束后续接也不重置。
    private final Map<Long, RootStream> roots = new ConcurrentHashMap<>();
    private final Map<Long, GroupMembers> groupMembers = new ConcurrentHashMap<>();

    public AgentStreamService(
        AgentRunMapper runs,
        AgentRunToolMapper toolRuns,
        GroupService groups,
        RealtimeHub realtime) {
        this.runs = runs;
        this.toolRuns = toolRuns;
        this.groups = groups;
        this.realtime = realtime;
    }

    /**
     * 注册运行的实时状态，并以即将开始的轮次广播开始事件。
     */
    public RunStreamSink sink(AgentRunEntity run) {
        RootStream root = roots.computeIfAbsent(rootRunId(run), ignored -> new RootStream());
        LiveRun state = live.computeIfAbsent(run.getId(), ignored -> new LiveRun(run, root));
        beginRound(state);
        return new Sink(state, true);
    }

    /**
     * 发布即将开始的这一轮；loop 的第一次 roundStarted 不再重复递增。
     */
    private void beginRound(LiveRun state) {
        synchronized (state) {
            if (state.closed) return;
            flush(state, true);
            int round = state.root.round(state.run.getId()).incrementAndGet();
            state.round = round;
            state.segments.add(new Segment(round));
            long revision = state.root.revision(state.run.getId()).incrementAndGet();
            emit(state, "agent-run.updated", updatedPayload(state, RunStatus.RUNNING, revision));
        }
        drainFrames(state);
    }

    /**
     * 群内最近的运行（含子运行），用于刷新与断线恢复。
     */
    public List<AgentRunSnapshot> recentRuns(
        Long projectId, Long groupId, Long userId, int limit) {
        groups.requireGroupMember(projectId, groupId, userId);
        List<AgentRunEntity> found =
            runs.selectList(
                    Wrappers.<AgentRunEntity>lambdaQuery()
                        .eq(AgentRunEntity::getProjectId, projectId)
                        .eq(AgentRunEntity::getGroupId, groupId)
                        .orderByDesc(AgentRunEntity::getCreatedAt)
                        .last("LIMIT " + limit));
        Map<Long, List<AgentRunToolEntity>> rows = toolRows(found);
        return found.stream()
            .map(run -> snapshot(run, rows.getOrDefault(run.getId(), List.of())))
            .toList();
    }

    /**
     * 必须在 state 的监视器内调用；把缓冲正文追加到当前分段并按需发一帧。
     *
     * @return 本次是否入队了 delta 帧，调用方据此决定锁外推送
     */
    private boolean flush(LiveRun state, boolean force) {
        if (state.pending.isEmpty()) return false;
        long now = System.currentTimeMillis();
        if (!force
            && state.pending.length() < FLUSH_CHARS
            && now - state.lastFlushAt < FLUSH_INTERVAL_MS) {
            return false;
        }
        String text = state.pending.toString();
        state.pending.setLength(0);
        state.lastFlushAt = now;
        if (state.segments.isEmpty()) state.segments.add(new Segment(state.round));
        state.segments.getLast().text.append(text);
        long sequence = state.root.seq(state.run.getId()).incrementAndGet();
        long revision = state.root.revision(state.run.getId()).incrementAndGet();
        Map<String, Object> payload = basePayload(state.run);
        payload.put("status", RunStatus.RUNNING.name());
        payload.put("round", state.round);
        payload.put("seq", sequence);
        payload.put("revision", revision);
        payload.put("text", text);
        emit(state, "agent-run.delta", payload);
        return true;
    }

    private Map<String, Object> updatedPayload(
        LiveRun state, RunStatus status, long revision) {
        Map<String, Object> payload = basePayload(state.run);
        payload.put("status", status.name());
        payload.put("round", state.round);
        payload.put("rounds", rounds(state.run));
        payload.put("revision", revision);
        return payload;
    }

    private Map<String, Object> toolPayload(
        LiveRun state,
        String phase,
        String toolCallId,
        String toolName,
        int round,
        Boolean ok,
        String text,
        long revision) {
        Map<String, Object> payload = basePayload(state.run);
        payload.put("status", RunStatus.RUNNING.name());
        payload.put("round", round);
        payload.put("revision", revision);
        payload.put("phase", phase);
        payload.put("toolCallId", toolCallId);
        payload.put("tool", toolName(toolName));
        payload.put("ok", ok);
        payload.put("started".equals(phase) ? "input" : "output", text);
        return payload;
    }

    /**
     * 必须在 state 监视器内调用：按 revision 顺序入队，锁外按同一顺序推送。
     */
    private void emit(LiveRun state, String type, Map<String, Object> payload) {
        state.pendingFrames.add(new PendingFrame(type, payload));
    }

    /**
     * 同一运行的事件串行广播，避免并行工具通知反序；不持有 state 锁做 IO。
     */
    private void drainFrames(LiveRun state) {
        synchronized (state.frameLock) {
            while (true) {
                PendingFrame frame;
                synchronized (state) {
                    frame = state.pendingFrames.poll();
                    if (frame == null) return;
                }
                broadcast(state.run, frame.type(), frame.payload());
            }
        }
    }

    /**
     * 推送给该群在线成员；帧结构与项目级实时事件一致，resourceId 为运行 ID。
     */
    private void broadcast(AgentRunEntity run, String type, Map<String, Object> payload) {
        realtime.broadcastToUsers(
            members(run.getGroupId()),
            RealtimeFrame.of(
                type,
                "project",
                run.getProjectId().toString(),
                run.getGroupId().toString(),
                null,
                null,
                run.getId().toString(),
                payload));
    }

    /**
     * 群成员推送目标；解析失败时沿用上一批缓存，推送问题不能影响 Agent 运行。
     */
    private Set<Long> members(Long groupId) {
        long now = System.currentTimeMillis();
        GroupMembers cached = groupMembers.get(groupId);
        if (cached != null && now - cached.loadedAt() < MEMBER_CACHE_MS) {
            return cached.userIds();
        }
        Set<Long> resolved;
        try {
            Set<Long> userIds = groups.memberUserIds(groupId);
            resolved = userIds == null ? Set.of() : Set.copyOf(userIds);
        } catch (RuntimeException failure) {
            log.warn(
                "agent run fan-out members unavailable, groupId={}: {}",
                groupId,
                failure.getMessage());
            return cached == null ? Set.of() : cached.userIds();
        }
        groupMembers.put(groupId, new GroupMembers(resolved, now));
        return resolved;
    }

    private Map<String, Object> basePayload(AgentRunEntity run) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", run.getId().toString());
        payload.put("parentRunId", id(run.getParentRunId()));
        payload.put("rootRunId", id(rootRunId(run)));
        payload.put("projectId", run.getProjectId().toString());
        payload.put("groupId", run.getGroupId().toString());
        payload.put("agentId", run.getAgentId().toString());
        payload.put("triggerMessageId", id(run.getTriggerMessageId()));
        return payload;
    }

    private AgentRunSnapshot snapshot(AgentRunEntity run, List<AgentRunToolEntity> rows) {
        LiveRun state = live.get(run.getId());
        RootStream root = roots.get(rootRunId(run));
        int round = root == null ? 0 : root.roundValue(run.getId());
        long revision = root == null ? 0 : root.revisionValue(run.getId());
        long seq = root == null ? 0 : root.seqValue(run.getId());
        List<AgentRunSnapshot.Segment> segments = List.of();
        if (state != null) {
            // 与 segments 在同一把锁内取版本号，避免迟到的快照把已有正文改回空。
            synchronized (state) {
                round = state.round;
                revision = state.root.revisionValue(run.getId());
                seq = state.root.seqValue(run.getId());
                segments = segments(state);
            }
        }
        return new AgentRunSnapshot(
            run.getId().toString(),
            id(run.getParentRunId()),
            id(rootRunId(run)),
            id(run.getProjectId()),
            id(run.getGroupId()),
            id(run.getAgentId()),
            id(run.getTriggerMessageId()),
            run.getStatus().name(),
            round,
            rounds(run),
            seq,
            revision,
            segments,
            tools(run, rows),
            run.getResult(),
            iso(run.getCreatedAt()),
            iso(run.getFinishedAt()));
    }

    private List<AgentRunSnapshot.Segment> segments(LiveRun state) {
        List<AgentRunSnapshot.Segment> result = new ArrayList<>(state.segments.size());
        for (Segment segment : state.segments) {
            if (!segment.text.isEmpty()) {
                result.add(new AgentRunSnapshot.Segment(segment.round, segment.text.toString()));
            }
        }
        return result;
    }

    /**
     * 按 run_id 一次取回工具明细，避免最近运行列表逐条查询。
     */
    private Map<Long, List<AgentRunToolEntity>> toolRows(List<AgentRunEntity> found) {
        if (found.isEmpty()) return Map.of();
        return toolRuns.byRunIds(found.stream().map(AgentRunEntity::getId).toList()).stream()
            .collect(Collectors.groupingBy(AgentRunToolEntity::getRunId));
    }

    private static List<AgentRunSnapshot.Tool> tools(
        AgentRunEntity run, List<AgentRunToolEntity> rows) {
        List<AgentRunSnapshot.Tool> result = new ArrayList<>(rows.size());
        for (AgentRunToolEntity row : rows) {
            result.add(
                new AgentRunSnapshot.Tool(
                    row.getToolCallId(),
                    row.getTool(),
                    row.getRound() == null ? 0 : row.getRound(),
                    toolState(row.getState(), run.getStatus()),
                    row.getInput(),
                    row.getOutput()));
        }
        return result;
    }

    /**
     * 运行已结束时不会再有工具事件，库里残留的 running 按中性的“结束”返回。
     */
    private static String toolState(String state, RunStatus runStatus) {
        boolean terminal = runStatus != RunStatus.RUNNING && runStatus != RunStatus.CANCELLING;
        return terminal && "running".equals(state) ? "done" : state;
    }

    private int rounds(AgentRunEntity run) {
        return run.getRounds() == null ? 0 : run.getRounds();
    }

    private static String toolName(String name) {
        return name != null && name.length() > TOOL_NAME_LIMIT
            ? name.substring(0, TOOL_NAME_LIMIT)
            : name;
    }

    private static String truncate(String text) {
        return text == null || text.length() <= TOOL_TEXT_LIMIT
            ? text
            : text.substring(0, TOOL_TEXT_LIMIT) + "…";
    }

    private static Long rootRunId(AgentRunEntity run) {
        return run.getParentRunId() == null ? run.getId() : run.getParentRunId();
    }

    private static String id(Long value) {
        return value == null ? null : value.toString();
    }

    private static String iso(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }

    /**
     * 同一根运行共享的单调计数器。
     */
    private static final class RootStream {
        private final Map<Long, AtomicInteger> rounds = new ConcurrentHashMap<>();
        private final Map<Long, AtomicLong> sequences = new ConcurrentHashMap<>();
        private final Map<Long, AtomicLong> revisions = new ConcurrentHashMap<>();

        AtomicInteger round(Long runId) {
            return rounds.computeIfAbsent(runId, ignored -> new AtomicInteger());
        }

        int roundValue(Long runId) {
            AtomicInteger value = rounds.get(runId);
            return value == null ? 0 : value.get();
        }

        AtomicLong seq(Long runId) {
            return sequences.computeIfAbsent(runId, ignored -> new AtomicLong());
        }

        long seqValue(Long runId) {
            AtomicLong value = sequences.get(runId);
            return value == null ? 0 : value.get();
        }

        AtomicLong revision(Long runId) {
            return revisions.computeIfAbsent(runId, ignored -> new AtomicLong());
        }

        long revisionValue(Long runId) {
            AtomicLong value = revisions.get(runId);
            return value == null ? 0 : value.get();
        }
    }

    /**
     * 单个运行的正文缓冲与轮次；运行结束后从 live 中移除，工具明细以库为准。
     */
    private static final class LiveRun {
        private final AgentRunEntity run;
        private final Long rootRunId;
        private final RootStream root;
        private final List<Segment> segments = new ArrayList<>();
        private final StringBuilder pending = new StringBuilder();
        private final Object frameLock = new Object();
        private final java.util.ArrayDeque<PendingFrame> pendingFrames =
            new java.util.ArrayDeque<>();
        private volatile int round;
        private long lastFlushAt = System.currentTimeMillis();
        private volatile boolean closed;

        LiveRun(AgentRunEntity run, RootStream root) {
            this.run = run;
            this.rootRunId = rootRunId(run);
            this.root = root;
        }
    }

    private static final class Segment {
        private final int round;
        private final StringBuilder text = new StringBuilder();

        Segment(int round) {
            this.round = round;
        }
    }

    private record PendingFrame(String type, Map<String, Object> payload) {
    }

    private record GroupMembers(Set<Long> userIds, long loadedAt) {
    }

    private final class Sink implements RunStreamSink {
        private final LiveRun state;
        // sink 建立时已为第一次模型调用预留轮次，loop 的首个 roundStarted 不再重复发布。
        private boolean roundOpened;

        Sink(LiveRun state, boolean roundOpened) {
            this.state = state;
            this.roundOpened = roundOpened;
        }

        public void roundStarted() {
            synchronized (state) {
                if (state.closed) return;
                if (roundOpened) {
                    roundOpened = false;
                    return;
                }
            }
            beginRound(state);
        }

        public void delta(String text) {
            if (text == null || text.isEmpty()) return;
            boolean emitted;
            synchronized (state) {
                if (state.closed) return;
                state.pending.append(text);
                emitted = flush(state, false);
            }
            if (emitted) drainFrames(state);
        }

        public void toolStarted(String toolCallId, String toolName, String input) {
            if (state.closed) return;
            int round = state.round;
            String text = truncate(input);
            AgentRunToolEntity row = new AgentRunToolEntity();
            row.setRunId(state.run.getId());
            row.setToolCallId(toolCallId);
            row.setTool(toolName(toolName));
            row.setRound(round);
            row.setState("running");
            row.setInput(text);
            row.setStartedAt(LocalDateTime.now(ZoneOffset.UTC));
            // 先落库再广播：随后读库的快照不会把这个工具当成不存在。
            toolRuns.insert(row);
            synchronized (state) {
                if (state.closed) return;
                flush(state, true);
                long revision = state.root.revision(state.run.getId()).incrementAndGet();
                emit(
                    state,
                    "agent-run.tool-started",
                    toolPayload(
                        state, "started", toolCallId, toolName, round, null, text, revision));
            }
            drainFrames(state);
        }

        public void toolFinished(String toolCallId, String toolName, Boolean ok, String output) {
            String text = truncate(output);
            // 运行已结束也要结算：库里残留 running 的会按 done 展示，这里能给出真实结果。
            toolRuns.finish(
                state.run.getId(),
                toolCallId,
                ok == null ? "done" : ok ? "ok" : "failed",
                text,
                LocalDateTime.now(ZoneOffset.UTC));
            if (state.closed) return;
            synchronized (state) {
                if (state.closed) return;
                flush(state, true);
                long revision = state.root.revision(state.run.getId()).incrementAndGet();
                emit(
                    state,
                    "agent-run.tool-finished",
                    toolPayload(
                        state,
                        "finished",
                        toolCallId,
                        toolName,
                        state.round,
                        ok,
                        text,
                        revision));
            }
            drainFrames(state);
        }

        public void finished() {
            synchronized (state) {
                if (state.closed) return;
                flush(state, true);
                state.closed = true;
                long revision = state.root.revision(state.run.getId()).incrementAndGet();
                emit(
                    state,
                    "agent-run.updated",
                    updatedPayload(state, state.run.getStatus(), revision));
            }
            live.remove(state.run.getId(), state);
            drainFrames(state);
            if (state.run.getParentRunId() == null) {
                roots.remove(state.rootRunId, state.root);
            }
        }

    }
}

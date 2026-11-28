package qg.qgent.harness.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.service.GroupService;
import qg.qgent.harness.dto.AgentRunSnapshot;
import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.harness.entity.AgentRunToolEntity;
import qg.qgent.harness.entity.FailureCode;
import qg.qgent.harness.entity.RunStatus;
import qg.qgent.harness.mapper.AgentRunMapper;
import qg.qgent.harness.mapper.AgentRunToolMapper;
import qg.qgent.realtime.service.RealtimeHub;
import qg.qgent.realtime.websocket.RealtimeFrame;
import qg.qgent.support.TestIds;

class AgentStreamServiceTest {
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final AgentRunToolMapper toolRuns = mock(AgentRunToolMapper.class);
    private final GroupService groups = mock(GroupService.class);
    private final RealtimeHub realtime = mock(RealtimeHub.class);
    private final AgentStreamService service = new AgentStreamService(runs, toolRuns, groups, realtime);
    // agent_run_tools 的内存替身：insert/finish/byRunIds 走真实写入路径。
    private final List<AgentRunToolEntity> toolRows = new CopyOnWriteArrayList<>();

    {
        when(toolRuns.insert(any(AgentRunToolEntity.class)))
                .thenAnswer(
                        invocation -> {
                            AgentRunToolEntity row = invocation.getArgument(0);
                            row.setId((long) toolRows.size() + 1);
                            toolRows.add(row);
                            return 1;
                        });
        when(toolRuns.byRunIds(any()))
                .thenAnswer(
                        invocation -> {
                            Collection<Long> runIds = invocation.getArgument(0);
                            return toolRows.stream()
                                    .filter(row -> runIds.contains(row.getRunId()))
                                    .toList();
                        });
        doAnswer(
                        invocation -> {
                            Long runId = invocation.getArgument(0);
                            String toolCallId = invocation.getArgument(1);
                            for (AgentRunToolEntity row : toolRows) {
                                if (row.getRunId().equals(runId)
                                        && row.getToolCallId().equals(toolCallId)) {
                                    row.setState(invocation.getArgument(2));
                                    row.setOutput(invocation.getArgument(3));
                                    row.setFinishedAt(invocation.getArgument(4));
                                }
                            }
                            return null;
                        })
                .when(toolRuns)
                .finish(any(), any(), any(), any(), any());
    }

    @Test
    void broadcastsSnapshotsDeltasAndToolFramesToTheGroupMembers() {
        when(groups.requireGroupMember(any(), any(), any())).thenReturn(new ChatGroupEntity());
        AgentRunEntity run = run();
        Long member = TestIds.next();
        when(groups.memberUserIds(run.getGroupId())).thenReturn(Set.of(member));
        RunStreamSink sink = service.sink(run);
        sink.roundStarted();
        sink.delta("正在检查仓库结构。");
        sink.toolStarted("call-a", "run_command", "{\"cmd\":\"ls\"}");
        sink.toolFinished("call-a", "run_command", true, "{\"ok\":true}");
        sink.delta("工具返回后继续输出。");
        sink.roundStarted();
        sink.delta("最终答复。");
        run.setStatus(RunStatus.SUCCEEDED);
        run.setRounds(2);
        sink.finished();

        verify(realtime, org.mockito.Mockito.atLeastOnce())
                .broadcastToUsers(eq(Set.of(member)), any(RealtimeFrame.class));
        List<RealtimeFrame> frames = frames();
        assertThat(frames)
                .extracting(RealtimeFrame::type)
                .containsExactly(
                        "agent-run.updated",
                        "agent-run.delta",
                        "agent-run.tool-started",
                        "agent-run.tool-finished",
                        "agent-run.delta",
                        "agent-run.updated",
                        "agent-run.delta",
                        "agent-run.updated");
        assertThat(frames).allSatisfy(frame -> {
            assertThat(frame.scope()).isEqualTo("project");
            assertThat(frame.projectId()).isEqualTo(run.getProjectId().toString());
            assertThat(frame.groupId()).isEqualTo(run.getGroupId().toString());
            assertThat(frame.resourceId()).isEqualTo(run.getId().toString());
        });
        assertThat(frames.stream().filter(frame -> "agent-run.delta".equals(frame.type())))
                .extracting(frame -> frame.payload().get("text"))
                .containsExactly("正在检查仓库结构。", "工具返回后继续输出。", "最终答复。");
        assertThat(toolFrame(frames, "agent-run.tool-started").payload())
                .containsEntry("toolCallId", "call-a")
                .containsEntry("tool", "run_command")
                .containsEntry("input", "{\"cmd\":\"ls\"}");
        assertThat(toolFrame(frames, "agent-run.tool-finished").payload())
                .containsEntry("toolCallId", "call-a")
                .containsEntry("output", "{\"ok\":true}");
        assertThat(frames.getLast().payload())
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("rounds", 2);
        assertThat(frames.getLast().payload().toString())
                .doesNotContain("reasoning", "arguments");
    }

    @Test
    void deltaPushesTheFrameAsSoonAsTheFlushThresholdIsReached() {
        when(groups.requireGroupMember(any(), any(), any())).thenReturn(new ChatGroupEntity());
        AgentRunEntity run = run();
        Long member = TestIds.next();
        when(groups.memberUserIds(run.getGroupId())).thenReturn(Set.of(member));
        RunStreamSink sink = service.sink(run);
        sink.roundStarted();

        // 达到 200 字阈值立即广播，不需要等到 finished() 或其他回合/工具事件。
        sink.delta("a".repeat(200));

        List<RealtimeFrame> deltas =
                frames().stream()
                        .filter(frame -> "agent-run.delta".equals(frame.type()))
                        .toList();
        assertThat(deltas).hasSize(1);
        assertThat(deltas.getFirst().payload())
                .containsEntry("runId", run.getId().toString())
                .containsEntry("round", 1)
                .containsEntry("seq", 1L)
                .containsEntry("text", "a".repeat(200));
    }

    @Test
    void snapshotCombinesLiveSegmentsWithDatabaseTerminalStateAndKeepsSeqAcrossResume() {
        when(groups.requireGroupMember(any(), any(), any())).thenReturn(new ChatGroupEntity());
        AgentRunEntity root = run();
        RunStreamSink rootSink = service.sink(root);
        rootSink.roundStarted();
        AgentRunEntity run = childOf(root);
        when(runs.selectList(any())).thenReturn(List.of(run));
        RunStreamSink sink = service.sink(run);

        sink.roundStarted();
        sink.delta("a");
        AgentRunSnapshot beforeFlush = recent(run);
        assertThat(beforeFlush.getSegments()).isEmpty();
        assertThat(beforeFlush.getSeq()).isZero();

        sink.toolStarted("call-1", "run_command", null);
        AgentRunSnapshot running = recent(run);
        assertThat(running.getRound()).isEqualTo(1);
        assertThat(running.getSeq()).isEqualTo(1);
        assertThat(running.getSegments())
                .extracting(AgentRunSnapshot.Segment::text)
                .containsExactly("a");
        assertThat(running.getTools())
                .extracting(AgentRunSnapshot.Tool::toolCallId, AgentRunSnapshot.Tool::state)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("call-1", "running"));

        sink.toolFinished("call-1", "run_command", true, null);
        run.setStatus(RunStatus.SUCCEEDED);
        run.setResult("a");
        run.setRounds(1);
        run.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
        sink.finished();
        AgentRunSnapshot finished = recent(run);
        assertThat(finished.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(finished.getResult()).isEqualTo("a");
        assertThat(finished.getRound()).isEqualTo(1);
        assertThat(finished.getSegments()).isEmpty();
        assertThat(finished.getTools())
                .extracting(AgentRunSnapshot.Tool::toolCallId, AgentRunSnapshot.Tool::state)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("call-1", "ok"));

        // 续接同一子运行：round 与 seq 到根运行结束前继续递增，前端不会把增量当过期。
        run.setStatus(RunStatus.RUNNING);
        RunStreamSink resumed = service.sink(run);
        resumed.roundStarted();
        resumed.delta("b");
        resumed.toolStarted("call-2", "run_command", null);
        AgentRunSnapshot afterResume = recent(run);
        assertThat(afterResume.getRound()).isEqualTo(2);
        assertThat(afterResume.getSeq()).isEqualTo(2);
        assertThat(afterResume.getRevision()).isGreaterThan(1);
        assertThat(afterResume.getSegments())
                .extracting(AgentRunSnapshot.Segment::text)
                .containsExactly("b");

        resumed.toolFinished("call-2", "run_command", false, null);
        resumed.finished();
        assertThat(updatesOf(run))
                .anySatisfy(
                        value -> {
                            assertThat(value).containsEntry("status", "RUNNING");
                            assertThat(value).containsEntry("round", 2);
                        });
    }

    @Test
    void snapshotsExposeCacheHitRateForZeroAndPositiveUsage() {
        when(groups.requireGroupMember(any(), any(), any())).thenReturn(new ChatGroupEntity());
        AgentRunEntity cached = run();
        cached.setStatus(RunStatus.SUCCEEDED);
        cached.setPromptTokens(200L);
        cached.setCachedTokens(150L);
        AgentRunEntity uncached = run();
        uncached.setStatus(RunStatus.SUCCEEDED);
        when(runs.selectList(any())).thenReturn(List.of(cached, uncached));

        List<AgentRunSnapshot> snapshots =
                service.recentRuns(cached.getProjectId(), cached.getGroupId(), TestIds.next(), 20);

        assertThat(snapshots)
                .extracting(
                        AgentRunSnapshot::getPromptTokens,
                        AgentRunSnapshot::getCachedTokens,
                        AgentRunSnapshot::getCacheHitRate)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(200L, 150L, 0.75),
                        org.assertj.core.groups.Tuple.tuple(0L, 0L, 0.0));
    }

    @Test
    void firstRoundIsReservedAsOneAndLoopDoesNotDuplicateIt() {
        when(groups.requireGroupMember(any(), any(), any())).thenReturn(new ChatGroupEntity());
        AgentRunEntity root = run();
        service.sink(root);
        AgentRunEntity child = childOf(root);

        RunStreamSink sink = service.sink(child);
        sink.roundStarted();

        List<Map<String, Object>> first = updatesOf(child);
        assertThat(first).hasSize(1);
        assertThat(first.getFirst())
                .containsEntry("runId", child.getId().toString())
                .containsEntry("status", "RUNNING")
                .containsEntry("round", 1)
                .containsEntry("revision", 1L);

        sink.roundStarted();
        List<Map<String, Object>> second = updatesOf(child);
        assertThat(second).hasSize(2);
        assertThat(second.getLast()).containsEntry("round", 2).containsEntry("revision", 2L);
    }

    @Test
    void everyStateChangeCarriesAStrictlyIncreasingRevision() {
        when(groups.requireGroupMember(any(), any(), any())).thenReturn(new ChatGroupEntity());
        AgentRunEntity root = run();
        service.sink(root);
        AgentRunEntity child = childOf(root);
        when(runs.selectList(any())).thenReturn(List.of(child));
        RunStreamSink sink = service.sink(child);
        AgentRunSnapshot started = recent(child);

        sink.roundStarted();
        sink.delta("a");
        sink.toolStarted("call-1", "run_command", null);
        AgentRunSnapshot toolStarted = recent(child);
        assertThat(toolStarted.getTools())
                .extracting(AgentRunSnapshot.Tool::toolCallId)
                .containsExactly("call-1");
        sink.toolFinished("call-1", "run_command", true, null);
        AgentRunSnapshot toolFinished = recent(child);
        assertThat(toolFinished.getTools())
                .extracting(AgentRunSnapshot.Tool::state)
                .containsExactly("ok");
        assertThat(toolFinished.getRevision()).isGreaterThan(toolStarted.getRevision());
        assertThat(toolStarted.getRevision()).isGreaterThan(started.getRevision());

        child.setStatus(RunStatus.SUCCEEDED);
        sink.finished();

        assertThat(
                        frames().stream()
                                .filter(frame ->
                                        child.getId()
                                                .toString()
                                                .equals(frame.payload().get("runId")))
                                .map(frame -> ((Number) frame.payload().get("revision")).longValue())
                                .toList())
                .isSorted()
                .doesNotHaveDuplicates();
    }

    @Test
    void terminalEventUsesRecordStatusWhenCancellationWins() {
        when(groups.requireGroupMember(any(), any(), any())).thenReturn(new ChatGroupEntity());
        AgentRunEntity run = run();
        RunStreamSink sink = service.sink(run);
        sink.roundStarted();
        run.setStatus(RunStatus.CANCELLED);
        run.setResult("Agent 运行已取消");
        sink.finished();

        assertThat(updatesOf(run).getLast())
                .containsEntry("status", "CANCELLED")
                .containsEntry("runId", run.getId().toString());
    }

    @Test
    void snapshotExposesFailureCodeOnAFailedRun() {
        when(groups.requireGroupMember(any(), any(), any())).thenReturn(new ChatGroupEntity());
        AgentRunEntity run = run();
        run.setStatus(RunStatus.FAILED);
        run.setFailureCode(FailureCode.UPSTREAM_TIMEOUT);

        AgentRunSnapshot snapshot = recent(run);

        assertThat(snapshot.getStatus()).isEqualTo("FAILED");
        assertThat(snapshot.getFailureCode()).isEqualTo("UPSTREAM_TIMEOUT");
    }

    @Test
    void keepsToolDetailsInRecentRunsAfterTheLiveRunIsCleanedUp() {
        when(groups.requireGroupMember(any(), any(), any())).thenReturn(new ChatGroupEntity());
        AgentRunEntity run = run();
        when(runs.selectList(any())).thenReturn(List.of(run));
        RunStreamSink sink = service.sink(run);
        sink.roundStarted();
        sink.toolStarted("call-1", "run_command", "{\"cmd\":\"ls\"}");
        sink.toolFinished("call-1", "run_command", true, "{\"ok\":true,\"stdout\":\"a\"}");
        sink.toolStarted("call-2", "read_file", "{\"path\":\"a.txt\"}");
        run.setStatus(RunStatus.SUCCEEDED);
        run.setResult("done");
        sink.finished();

        AgentRunSnapshot snapshot = recent(run);
        assertThat(snapshot.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(snapshot.getTools())
                .containsExactly(
                        new AgentRunSnapshot.Tool(
                                "call-1",
                                "run_command",
                                1,
                                "ok",
                                "{\"cmd\":\"ls\"}",
                                "{\"ok\":true,\"stdout\":\"a\"}"),
                        // 运行结束时仍 running 的工具按 done 返回。
                        new AgentRunSnapshot.Tool(
                                "call-2", "read_file", 1, "done", "{\"path\":\"a.txt\"}", null));

        List<RealtimeFrame> frames = frames();
        assertThat(toolFrame(frames, "agent-run.tool-started").payload())
                .containsEntry("tool", "run_command")
                .containsEntry("input", "{\"cmd\":\"ls\"}");
        assertThat(toolFrame(frames, "agent-run.tool-finished").payload())
                .containsEntry("output", "{\"ok\":true,\"stdout\":\"a\"}");
    }

    @Test
    void truncatesToolInputAndOutputToTwoThousandCharacters() {
        when(groups.requireGroupMember(any(), any(), any())).thenReturn(new ChatGroupEntity());
        AgentRunEntity run = run();
        when(runs.selectList(any())).thenReturn(List.of(run));
        RunStreamSink sink = service.sink(run);
        sink.roundStarted();
        sink.toolStarted("call-1", "run_command", "x".repeat(2500));
        sink.toolFinished("call-1", "run_command", false, "y".repeat(2500));

        AgentRunSnapshot.Tool tool = recent(run).getTools().getFirst();
        assertThat(tool.state()).isEqualTo("failed");
        assertThat(tool.input()).isEqualTo("x".repeat(2000) + "…");
        assertThat(tool.output()).isEqualTo("y".repeat(2000) + "…");
    }

    @Test
    void memberLookupFailureKeepsTheRunAndPublishesNoAudience() {
        when(groups.requireGroupMember(any(), any(), any())).thenReturn(new ChatGroupEntity());
        AgentRunEntity run = run();
        when(groups.memberUserIds(run.getGroupId()))
                .thenThrow(new IllegalStateException("group removed"));
        RunStreamSink sink = service.sink(run);
        sink.roundStarted();
        sink.delta("a");
        run.setStatus(RunStatus.SUCCEEDED);
        sink.finished();

        verify(realtime, org.mockito.Mockito.atLeastOnce())
                .broadcastToUsers(eq(Set.of()), any(RealtimeFrame.class));
        assertThat(recent(run).getStatus()).isEqualTo("SUCCEEDED");
    }

    private AgentRunSnapshot recent(AgentRunEntity run) {
        when(groups.requireGroupMember(any(), any(), any())).thenReturn(new ChatGroupEntity());
        when(runs.selectList(any())).thenReturn(List.of(run));
        return service.recentRuns(run.getProjectId(), run.getGroupId(), TestIds.next(), 20)
                .getFirst();
    }

    @SuppressWarnings("unchecked")
    private List<RealtimeFrame> frames() {
        ArgumentCaptor<RealtimeFrame> sent = ArgumentCaptor.forClass(RealtimeFrame.class);
        verify(realtime, org.mockito.Mockito.atLeastOnce())
                .broadcastToUsers(any(), sent.capture());
        return sent.getAllValues();
    }

    private static RealtimeFrame toolFrame(List<RealtimeFrame> frames, String type) {
        return frames.stream()
                .filter(frame -> type.equals(frame.type()))
                .findFirst()
                .orElseThrow();
    }

    private List<Map<String, Object>> updatesOf(AgentRunEntity run) {
        return frames().stream()
                .filter(frame -> "agent-run.updated".equals(frame.type()))
                .map(RealtimeFrame::payload)
                .filter(value -> run.getId().toString().equals(value.get("runId")))
                .toList();
    }

    private AgentRunEntity childOf(AgentRunEntity root) {
        AgentRunEntity child = run();
        child.setProjectId(root.getProjectId());
        child.setGroupId(root.getGroupId());
        child.setAgentId(root.getAgentId());
        child.setTriggerMessageId(root.getTriggerMessageId());
        child.setParentRunId(root.getId());
        return child;
    }

    private AgentRunEntity run() {
        AgentRunEntity run = new AgentRunEntity();
        run.setId(TestIds.next());
        run.setProjectId(TestIds.next());
        run.setGroupId(TestIds.next());
        run.setAgentId(TestIds.next());
        run.setTriggerMessageId(TestIds.next());
        run.setStatus(RunStatus.RUNNING);
        run.setRounds(0);
        run.setPromptTokens(0L);
        run.setCachedTokens(0L);
        run.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return run;
    }
}

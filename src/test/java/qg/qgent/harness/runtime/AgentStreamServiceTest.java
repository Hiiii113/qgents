package qg.qgent.harness.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.service.GroupService;
import qg.qgent.harness.dto.AgentRunSnapshot;
import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.harness.entity.RunStatus;
import qg.qgent.harness.mapper.AgentRunMapper;
import qg.qgent.realtime.service.EventService;
import qg.qgent.support.TestIds;

class AgentStreamServiceTest {
    private final ExecutorService pump = Executors.newSingleThreadExecutor();
    private final EventService events = mock(EventService.class);
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final GroupService groups = mock(GroupService.class);
    private final SseEmitter emitter = mock(SseEmitter.class);
    private final AgentStreamService service =
            new AgentStreamService(events, runs, groups, timeout -> emitter, pump);

    @AfterEach
    void stopPump() {
        pump.shutdownNow();
    }

    @Test
    void streamsSnapshotsDeltasAndToolEventsWithCallIds() throws Exception {
        when(groups.requireGroupMember(any(), any(), any())).thenReturn(new ChatGroupEntity());
        AgentRunEntity run = run();
        RunStreamSink sink = service.sink(run);
        sink.roundStarted();
        service.openGroupStream(run.getProjectId(), run.getGroupId(), TestIds.next());
        sink.delta("正在检查仓库结构。");
        sink.toolStarted("call-a", "run_command");
        sink.toolFinished("call-a", "run_command", true);
        sink.delta("工具返回后继续输出。");
        sink.roundStarted();
        sink.delta("最终答复。");
        run.setStatus(RunStatus.SUCCEEDED);
        run.setRounds(2);
        sink.finished();

        verify(emitter, timeout(3000).atLeast(5)).send(any(SseEmitter.SseEventBuilder.class));
        String frames = sentFrames();
        assertThat(frames)
                .contains("event:agent-run.snapshot")
                .contains("event:agent-run.updated")
                .contains("event:agent-run.delta")
                .contains("event:agent-run.tool")
                .contains("toolCallId=call-a")
                .contains("phase=started")
                .contains("phase=finished")
                .contains("text=正在检查仓库结构。")
                .contains("status=SUCCEEDED")
                .contains("rounds=2")
                .contains("revision=");
        assertThat(frames).doesNotContain("reasoning", "arguments");
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

        sink.toolStarted("call-1", "run_command");
        AgentRunSnapshot running = recent(run);
        assertThat(running.getRound()).isEqualTo(1);
        assertThat(running.getSeq()).isEqualTo(1);
        assertThat(running.getSegments())
                .extracting(AgentRunSnapshot.Segment::text)
                .containsExactly("a");
        assertThat(running.getTools())
                .extracting(AgentRunSnapshot.Tool::toolCallId)
                .containsExactly("call-1");

        sink.toolFinished("call-1", "run_command", true);
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
        assertThat(finished.getTools()).isEmpty();

        // 续接同一子运行：round 与 seq 到根运行结束前继续递增，前端不会把增量当过期。
        run.setStatus(RunStatus.RUNNING);
        RunStreamSink resumed = service.sink(run);
        resumed.roundStarted();
        resumed.delta("b");
        resumed.toolStarted("call-2", "run_command");
        AgentRunSnapshot afterResume = recent(run);
        assertThat(afterResume.getRound()).isEqualTo(2);
        assertThat(afterResume.getSeq()).isEqualTo(2);
        assertThat(afterResume.getRevision()).isGreaterThan(1);
        assertThat(afterResume.getSegments())
                .extracting(AgentRunSnapshot.Segment::text)
                .containsExactly("b");

        resumed.toolFinished("call-2", "run_command", false);
        resumed.finished();
        ArgumentCaptor<java.util.Map<String, Object>> payload =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(events, org.mockito.Mockito.atLeastOnce())
                .publish(
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.eq("agent-run.updated"),
                        any(),
                        payload.capture());
        assertThat(payload.getAllValues())
                .anySatisfy(
                        value -> {
                            assertThat(value.get("status")).isEqualTo("RUNNING");
                            assertThat(value.get("round")).isEqualTo(2);
                        });
    }

    @Test
    void firstRoundIsReservedAsOneAndLoopDoesNotDuplicateIt() {
        when(groups.requireGroupMember(any(), any(), any())).thenReturn(new ChatGroupEntity());
        AgentRunEntity root = run();
        service.sink(root);
        AgentRunEntity child = childOf(root);

        RunStreamSink sink = service.sink(child);
        sink.roundStarted();

        List<java.util.Map<String, Object>> first = updatesOf(child);
        assertThat(first).hasSize(1);
        assertThat(first.getFirst())
                .containsEntry("runId", child.getId().toString())
                .containsEntry("status", "RUNNING")
                .containsEntry("round", 1)
                .containsEntry("revision", 1L);

        sink.roundStarted();
        List<java.util.Map<String, Object>> second = updatesOf(child);
        assertThat(second).hasSize(2);
        assertThat(second.getLast()).containsEntry("round", 2).containsEntry("revision", 2L);
    }

    private List<java.util.Map<String, Object>> updatesOf(AgentRunEntity run) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> payload =
                ArgumentCaptor.forClass(java.util.Map.class);
        org.mockito.Mockito.verify(events, org.mockito.Mockito.atLeastOnce())
                .publish(
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.eq("agent-run.updated"),
                        any(),
                        payload.capture());
        return payload.getAllValues().stream()
                .filter(value -> run.getId().toString().equals(value.get("runId")))
                .toList();
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
        sink.toolStarted("call-1", "run_command");
        AgentRunSnapshot toolStarted = recent(child);
        assertThat(toolStarted.getTools())
                .extracting(AgentRunSnapshot.Tool::toolCallId)
                .containsExactly("call-1");
        sink.toolFinished("call-1", "run_command", true);
        AgentRunSnapshot toolFinished = recent(child);
        assertThat(toolFinished.getTools()).isEmpty();
        assertThat(toolFinished.getRevision()).isGreaterThan(toolStarted.getRevision());
        assertThat(toolStarted.getRevision()).isGreaterThan(started.getRevision());

        child.setStatus(RunStatus.SUCCEEDED);
        sink.finished();

        ArgumentCaptor<java.util.Map<String, Object>> payload =
                ArgumentCaptor.forClass(java.util.Map.class);
        org.mockito.Mockito.verify(events, org.mockito.Mockito.atLeastOnce())
                .publish(
                        any(),
                        any(),
                        any(),
                        any(),
                        payload.capture());
        assertThat(
                        payload.getAllValues().stream()
                                .filter(value -> child.getId().toString().equals(value.get("runId")))
                                .map(value -> ((Number) value.get("revision")).longValue())
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

        ArgumentCaptor<java.util.Map<String, Object>> payload =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(events, org.mockito.Mockito.atLeastOnce())
                .publish(
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.eq("agent-run.updated"),
                        any(),
                        payload.capture());
        assertThat(payload.getAllValues().getLast())
                .containsEntry("status", "CANCELLED")
                .containsEntry("runId", run.getId().toString());
    }

    @Test
    void deniesGroupStreamForUsersOutsideTheGroup() {
        when(groups.requireGroupMember(any(), any(), any()))
                .thenThrow(
                        new qg.qgent.common.web.ApiException(
                                org.springframework.http.HttpStatus.NOT_FOUND,
                                "GROUP_NOT_FOUND",
                                "群不存在或无权访问"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.openGroupStream(TestIds.next(), TestIds.next(), TestIds.next()))
                .isInstanceOf(qg.qgent.common.web.ApiException.class);
    }

    @Test
    void slowClientIsDisconnectedInsteadOfAccumulatingFrames() throws Exception {
        when(groups.requireGroupMember(any(), any(), any())).thenReturn(new ChatGroupEntity());
        AgentStreamService blocked = new AgentStreamService(events, runs, groups, timeout -> emitter, task -> {});
        AgentRunEntity run = run();
        blocked.openGroupStream(run.getProjectId(), run.getGroupId(), TestIds.next());
        RunStreamSink sink = blocked.sink(run);
        sink.roundStarted();
        for (int i = 0; i < 400; i++) {
            sink.delta("x".repeat(200));
        }

        verify(emitter, timeout(2000)).complete();
    }

    private AgentRunSnapshot recent(AgentRunEntity run) {
        return service.recentRuns(run.getProjectId(), run.getGroupId(), TestIds.next(), 20)
                .getFirst();
    }

    private String sentFrames() throws Exception {
        ArgumentCaptor<SseEmitter.SseEventBuilder> sent =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, org.mockito.Mockito.atLeastOnce()).send(sent.capture());
        return sent.getAllValues().stream()
                .map(
                        builder ->
                                builder.build().stream()
                                        .map(item -> String.valueOf(item.getData()))
                                        .collect(Collectors.joining()))
                .collect(Collectors.joining("\n"));
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
        run.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return run;
    }
}

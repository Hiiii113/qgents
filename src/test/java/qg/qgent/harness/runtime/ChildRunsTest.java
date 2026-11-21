package qg.qgent.harness.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.harness.entity.RunStatus;
import qg.qgent.harness.service.AgentRunService;
import qg.qgent.sandbox.service.SandboxSessionManager;
import qg.qgent.support.TestIds;

class ChildRunsTest {

    private static final int NOTIFIED_RESULT_LIMIT = 4000;
    private static final String TRUNCATED_MARKER = "\n…（已截断，完整结果见子运行记录）";

    @Test
    void bindingTheLazyDefaultWorkspacePreservesTheAgentDirectory() {
        ChildRuns.Execution execution =
                new ChildRuns.Execution(new AgentRunEntity(), null, null, ".");
        Long workspace = TestIds.next();
        execution.workspace.setDirectory("src/runtime");

        execution.workspace.bindDefault(workspace);

        assertThat(execution.workspace.workspaceId()).isEqualTo(workspace);
        assertThat(execution.workspace.directory()).isEqualTo("src/runtime");
    }

    @Test
    void explicitlySelectingAnotherWorkspaceResetsTheAgentDirectory() {
        ChildRuns.Execution execution =
                new ChildRuns.Execution(new AgentRunEntity(), null, TestIds.next(), "src/runtime");

        execution.workspace.select(TestIds.next());

        assertThat(execution.workspace.directory()).isEqualTo(".");
    }

    @Test
    void resumingAStoppedChildAllowsItsCommandsToBeCancelledAgain() {
        Long runId = TestIds.next();
        AgentRunEntity run = new AgentRunEntity();
        run.setId(runId);
        ChildRuns children = children(runId);
        ChildRuns.Execution child = new ChildRuns.Execution(run, null, null, ".");
        assertThat(children.reserve(child)).isTrue();
        child.schedule();
        assertThat(children.requestStop(child)).isTrue();
        assertThat(children.markCommandsCancelled(runId)).isTrue();
        assertThat(children.markCommandsCancelled(runId)).isFalse();
        assertThat(child.beginFinishing()).isTrue();
        run.setStatus(RunStatus.CANCELLED);
        assertThat(children.publishFinish(child)).isTrue();

        assertThat(children.reserveContinuation(child)).isTrue();

        assertThat(children.markCommandsCancelled(runId)).isTrue();
    }

    @Test
    void longChildResultsAreTruncatedInTheParentNotificationButNotInTheRun() {
        Long runId = TestIds.next();
        AgentRunEntity run = new AgentRunEntity();
        run.setId(runId);
        // 让 emoji 的代理对正好落在截断点上。
        int cut = NOTIFIED_RESULT_LIMIT - TRUNCATED_MARKER.length();
        String result = "a".repeat(cut - 1) + "😀" + "尾部内容".repeat(100);
        run.setStatus(RunStatus.SUCCEEDED);
        run.setResult(result);
        ChildRuns children = children(runId);
        ChildRuns.Execution child = new ChildRuns.Execution(run, null, null, ".");
        assertThat(children.reserve(child)).isTrue();
        child.schedule();
        assertThat(child.beginFinishing()).isTrue();

        assertThat(children.publishFinish(child)).isTrue();

        UserMessage notification = (UserMessage) children.updates().drain().getFirst();
        assertThat(notification.getText())
                .contains("子 Agent " + runId + " 已结束，状态：SUCCEEDED")
                .endsWith("执行结果：\n" + "a".repeat(cut - 1) + TRUNCATED_MARKER);
        assertThat(notification.getText()).doesNotContain("尾部内容");
        assertThat(run.getResult()).isEqualTo(result);
    }

    private ChildRuns children(Long runId) {
        return new ChildRuns(
                runId,
                1,
                Runnable::run,
                mock(AgentRunService.class),
                new RunLifecycle(
                        mock(AgentRunService.class),
                        mock(SandboxSessionManager.class),
                        new WorkContext(null, null, null, runId, false)),
                () -> false,
                mock(AgentLoop.class),
                child -> List.of(),
                child -> RunStreamSink.NONE);
    }
}

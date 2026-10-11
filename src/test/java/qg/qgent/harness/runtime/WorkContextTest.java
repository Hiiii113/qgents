package qg.qgent.harness.runtime;

import org.junit.jupiter.api.Test;
import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.chat.event.MessageSentEvent;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkContextTest {
    @Test
    void bindingTheLazyDefaultWorkspacePreservesTheAgentDirectory() {
        WorkContext.AgentExecution execution = new WorkContext.AgentExecution(
                new AgentRunEntity(), null, null, ".");
        UUID workspace = UUID.randomUUID();
        execution.workspace.setDirectory("src/runtime");

        execution.workspace.bindDefault(workspace);

        assertThat(execution.workspace.workspaceId()).isEqualTo(workspace);
        assertThat(execution.workspace.directory()).isEqualTo("src/runtime");
    }

    @Test
    void explicitlySelectingAnotherWorkspaceResetsTheAgentDirectory() {
        WorkContext.AgentExecution execution = new WorkContext.AgentExecution(
                new AgentRunEntity(), null, UUID.randomUUID(), "src/runtime");

        execution.workspace.select(UUID.randomUUID());

        assertThat(execution.workspace.directory()).isEqualTo(".");
    }

    @Test
    void resumingAStoppedChildAllowsItsCommandsToBeCancelledAgain() {
        UUID runId = UUID.randomUUID();
        AgentRunEntity run = new AgentRunEntity();
        run.setId(runId);
        WorkContext context = new WorkContext(
                new MessageSentEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), java.util.List.of()),
                UUID.randomUUID());
        WorkContext.AgentExecution child = new WorkContext.AgentExecution(run, null, null, ".");
        assertThat(context.reserveChild(child, 1)).isTrue();
        child.schedule();
        assertThat(context.requestChildStop(child)).isTrue();
        assertThat(context.markCommandsCancelled(runId)).isTrue();
        assertThat(context.markCommandsCancelled(runId)).isFalse();
        assertThat(child.beginFinishing()).isTrue();
        run.setStatus("CANCELLED");
        assertThat(context.publishChildFinish(child)).isTrue();

        assertThat(context.reserveContinuation(child, 1)).isTrue();

        assertThat(context.markCommandsCancelled(runId)).isTrue();
    }
}

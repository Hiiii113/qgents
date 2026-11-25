package qg.qgent.sandbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import qg.qgent.sandbox.execution.entity.ExecutionStatus;
import qg.qgent.sandbox.execution.output.CommandOutputWindow;
import qg.qgent.sandbox.execution.output.CommandStreamWindow;
import qg.qgent.sandbox.execution.service.CommandExecution;
import qg.qgent.sandbox.execution.service.CommandExecutionService;
import qg.qgent.support.TestIds;

class SandboxCommandsTest {
    private final Long id = TestIds.next(), execution = TestIds.next();
    private final UUID sandbox = UUID.randomUUID();
    private final CommandExecutionService executions = mock(CommandExecutionService.class);
    private final SandboxSessionManager sessions = mock(SandboxSessionManager.class);
    private final SandboxCommands commands = new SandboxCommands(sessions, executions);

    private CommandExecution execution(ExecutionStatus status, Integer exitCode) {
        when(sessions.require(id))
            .thenReturn(new SandboxSession(TestIds.next(), id, sandbox, "key"));
        var value =
            new CommandExecution(
                execution, sandbox, "command.run", status, exitCode, Map.of(), null, null);
        when(executions.start(
                eq(sandbox), any(), eq(id), any(), any(), any(), anyMap()))
            .thenReturn(value);
        when(executions.find(execution)).thenReturn(value);
        when(executions.await(eq(execution), any())).thenReturn(value);
        return value;
    }

    @Test
    void immediateWaitReturnsRunningHandleWithoutStartingAgain() {
        execution(ExecutionStatus.RUNNING, null);
        assertThat(commands.start(id, ".", List.of("npm", "test"), 600, 0))
            .containsEntry("running", true)
            .containsEntry("executionId", Long.toString(execution));
        verify(executions, times(1))
            .start(eq(sandbox), any(), eq(id), any(), any(), any(), anyMap());
    }

    @Test
    void nonzeroExitIsReturnedAsCompletedCommand() {
        execution(ExecutionStatus.SUCCEEDED, 5);
        assertThat(commands.start(id, ".", List.of("npm", "test"), 600, 0))
            .containsEntry("ok", true)
            .containsEntry("exitCode", 5)
            .containsEntry("running", false);
    }

    @Test
    void outputUsesByteCursorsAndRenewsEnvironment() {
        execution(ExecutionStatus.RUNNING, null);
        when(executions.output(execution, 30, 40, 16000))
            .thenReturn(
                new CommandOutputWindow(
                    new CommandStreamWindow("next", 34L, true),
                    new CommandStreamWindow("err", 12L, false)));
        assertThat(commands.output(id, execution, 30, 40, 16000, 0))
            .containsEntry("running", true)
            .containsKey("stdout");
        verify(executions).output(execution, 30, 40, 16000);
        verify(executions, never())
            .start(any(), any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void foreignExecutionCannotBeReadOrCancelled() {
        var value = execution(ExecutionStatus.RUNNING, null);
        var foreign =
            new CommandExecution(
                value.id(),
                UUID.randomUUID(),
                "command.run",
                value.status(),
                value.exitCode(),
                value.result(),
                null,
                null);
        when(executions.find(execution)).thenReturn(foreign);
        assertThatThrownBy(() -> commands.output(id, execution, 0, 0, 16000, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> commands.cancel(id, execution))
            .isInstanceOf(IllegalArgumentException.class);
        verify(executions, never()).cancel(any());
        verify(executions, never()).output(any(), anyLong(), anyLong(), anyInt());
    }

    @Test
    void runForwardsTheRequestedDirectory() {
        execution(ExecutionStatus.SUCCEEDED, 0);
        commands.run(id, "backend", List.of("git", "push"), Map.of());
        verify(executions)
            .start(
                eq(sandbox),
                isNull(),
                eq(id),
                eq("backend"),
                eq(List.of("git", "push")),
                any(Duration.class),
                anyMap());
    }

    @Test
    void gitRunsFromWorkspaceRoot() {
        execution(ExecutionStatus.SUCCEEDED, 0);
        assertThat(commands.git(id, List.of("git", "fetch"), Map.of()).exitCode()).isZero();
        verify(executions)
            .start(
                eq(sandbox),
                isNull(),
                eq(id),
                eq("."),
                eq(List.of("git", "fetch")),
                any(Duration.class),
                anyMap());
    }

    @Test
    void cancelActiveCommandAndRepeatedCancelTerminalCommand() {
        execution(ExecutionStatus.RUNNING, null);
        var cancelled =
            new CommandExecution(
                execution,
                sandbox,
                "command.run",
                ExecutionStatus.CANCELLED,
                null,
                Map.of(),
                null,
                null);
        when(executions.cancel(execution)).thenReturn(cancelled);
        assertThat(commands.cancel(id, execution))
            .containsEntry("status", "CANCELLED")
            .containsEntry("running", false);
        execution(ExecutionStatus.SUCCEEDED, 0);
        assertThat(commands.cancel(id, execution)).containsEntry("exitCode", 0);
        verify(executions, times(1)).cancel(execution);
    }

    @Test
    void emptyCommandIsRejectedBeforeSubmission() {
        when(sessions.require(id))
            .thenReturn(new SandboxSession(TestIds.next(), id, sandbox, "key"));

        assertThatThrownBy(() -> commands.start(id, ".", List.of(), 600, 0))
            .isInstanceOf(IllegalArgumentException.class);

        verify(executions, never())
            .start(any(), any(), any(), any(), any(), any(), anyMap());
    }
}

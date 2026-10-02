package qg.qgent.sandbox.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import qg.qgent.agent.service.AgentWorkerExecutionService;
import qg.qgent.sandbox.client.SandboxWorkerClient;
import qg.qgent.sandbox.service.SandboxSessionManager;
import qg.qgent.sandbox.service.WorkerWorkspaceCodeAccess;
import qg.qgent.sandbox.service.WorkerWorkspaceCodeWriter;
import qg.qgent.sandbox.service.WorkerWorkspaceCommands;
import qg.qgent.workspace.service.WorkspaceCodeAccess;
import qg.qgent.workspace.service.WorkspaceCodeWriter;
import qg.qgent.workspace.service.WorkspaceCommands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 默认装配全部 Worker 端口，无需额外开启执行开关。 */
class WorkerPortSelectionTest {
    @Test
    void workspaceOperationsAlwaysUseWorker() {
        new ApplicationContextRunner()
                .withBean(SandboxWorkerClient.class, () -> mock(SandboxWorkerClient.class))
                .withBean(SandboxSessionManager.class, () -> mock(SandboxSessionManager.class))
                .withBean(SandboxWorkerProperties.class, SandboxWorkerProperties::new)
                .withBean(AgentWorkerExecutionService.class, () -> mock(AgentWorkerExecutionService.class))
                .withUserConfiguration(Ports.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(WorkspaceCodeAccess.class)
                            .hasSingleBean(WorkspaceCodeWriter.class).hasSingleBean(WorkspaceCommands.class);
                    assertThat(context.getBean(WorkspaceCodeAccess.class)).isInstanceOf(WorkerWorkspaceCodeAccess.class);
                    assertThat(context.getBean(WorkspaceCodeWriter.class)).isInstanceOf(WorkerWorkspaceCodeWriter.class);
                    assertThat(context.getBean(WorkspaceCommands.class)).isInstanceOf(WorkerWorkspaceCommands.class);
                });
    }

    @Configuration
    @Import({WorkerWorkspaceCodeAccess.class, WorkerWorkspaceCodeWriter.class,
            WorkerWorkspaceCommands.class})
    static class Ports {
    }
}

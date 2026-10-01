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
import qg.qgent.sandbox.service.WorkerWorkspaceDiffAccess;
import qg.qgent.workspace.mapper.WorkspaceMapper;
import qg.qgent.workspace.mapper.WorkspaceRepositoryMapper;
import qg.qgent.workspace.service.LocalGitDiffAccess;
import qg.qgent.workspace.service.LocalWorkspaceCodeAccess;
import qg.qgent.workspace.service.LocalWorkspaceCodeWriter;
import qg.qgent.workspace.service.WorkspaceCodeAccess;
import qg.qgent.workspace.service.WorkspaceCodeWriter;
import qg.qgent.workspace.service.WorkspaceDiffAccess;
import qg.qgent.workspace.service.WorkspaceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 验证 {@code app.worker.enabled} 开关在两个模式下选中的端口实现：
 * 关闭时用本地 Local*，
 * 开启时用 Worker*。纯 Bean 装配检查，不启动完整 Spring Boot、不访问 DB/Redis。
 */
class WorkerPortSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(SandboxWorkerClient.class, () -> mock(SandboxWorkerClient.class))
            .withBean(SandboxSessionManager.class, () -> mock(SandboxSessionManager.class))
            .withBean(SandboxWorkerProperties.class, SandboxWorkerProperties::new)
            .withBean(WorkspaceRepositoryMapper.class, () -> mock(WorkspaceRepositoryMapper.class))
            .withBean(WorkspaceService.class, () -> new WorkspaceService(mock(WorkspaceMapper.class), null))
            .withBean(AgentWorkerExecutionService.class, () -> mock(AgentWorkerExecutionService.class))
            .withUserConfiguration(Ports.class);

    @Test
    void localPortsSelectedWhenWorkerDisabled() {
        runner.withPropertyValues("app.worker.enabled=false").run(context -> {
            assertThat(context.getBean(WorkspaceCodeAccess.class)).isInstanceOf(LocalWorkspaceCodeAccess.class);
            assertThat(context.getBean(WorkspaceCodeWriter.class)).isInstanceOf(LocalWorkspaceCodeWriter.class);
            assertThat(context.getBean(WorkspaceDiffAccess.class)).isInstanceOf(LocalGitDiffAccess.class);
        });
    }

    @Test
    void workerPortsSelectedWhenWorkerEnabled() {
        runner.withPropertyValues("app.worker.enabled=true").run(context -> {
            assertThat(context.getBean(WorkspaceCodeAccess.class)).isInstanceOf(WorkerWorkspaceCodeAccess.class);
            assertThat(context.getBean(WorkspaceCodeWriter.class)).isInstanceOf(WorkerWorkspaceCodeWriter.class);
            assertThat(context.getBean(WorkspaceDiffAccess.class)).isInstanceOf(WorkerWorkspaceDiffAccess.class);
        });
    }

    @Configuration
    @Import({
            LocalWorkspaceCodeAccess.class, LocalWorkspaceCodeWriter.class,
            LocalGitDiffAccess.class,
            WorkerWorkspaceCodeAccess.class, WorkerWorkspaceCodeWriter.class,
            WorkerWorkspaceDiffAccess.class })
    static class Ports {
    }
}

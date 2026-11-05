package qg.qgent.workspace.service;

import qg.qgent.support.TestIds;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.mapper.WorkspaceMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 工作区记录创建和选择校验，不准备物理目录或执行环境。 */
class WorkspaceServiceTest {

    @Test
    void creatingWorkspaceOnlyStoresMetadata(@TempDir Path baseDir) throws Exception {
        WorkspaceService service = new WorkspaceService(mapper);
        Long projectId = TestIds.next();
        WorkspaceEntity created = service.create(projectId);
        assertThat(created.getProjectId()).isEqualTo(projectId);
        assertThat(created.getStatus()).isEqualTo("PROVISIONING");
        org.mockito.Mockito.verify(mapper).insert(created);
        assertThat(Files.exists(baseDir.resolve(created.getStorageKey()))).isFalse();
    }

    @Test
    void selectingWorkspaceChecksProjectAndAvailability() {
        WorkspaceEntity existing = workspace("workspaces/example");
        Long projectId = TestIds.next();
        existing.setProjectId(projectId);
        existing.setStatus("READY");
        when(mapper.selectById(existing.getId())).thenReturn(existing);
        WorkspaceService service = new WorkspaceService(mapper);
        assertThat(service.require(projectId, existing.getId())).isSameAs(existing);
        assertThatThrownBy(() -> service.require(TestIds.next(), existing.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        existing.setStatus("ARCHIVED");
        assertThatThrownBy(() -> service.require(projectId, existing.getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private final WorkspaceMapper mapper = mock(WorkspaceMapper.class);

    private WorkspaceEntity workspace(String storageKey) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(TestIds.next());
        workspace.setStorageKey(storageKey);
        return workspace;
    }

}

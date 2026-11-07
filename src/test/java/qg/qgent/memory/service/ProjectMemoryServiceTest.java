package qg.qgent.memory.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import qg.qgent.common.web.ApiException;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.support.TestIds;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 项目 memory.md：首次创建、版本检测、字符预算、项目隔离与成员校验。 */
class ProjectMemoryServiceTest {

    private static final int MAX_CHARS = 100;

    @TempDir
    Path root;

    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final EventService events = mock(EventService.class);
    private ProjectMemoryService service;

    @BeforeEach
    void setUp() {
        service = new ProjectMemoryService(access, new ProjectDirectoryService(root.toString(), root.toString()), events, MAX_CHARS);
    }

    @Test
    void firstReadCreatesEmptyMemoryFileInProjectDirectory() throws Exception {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();

        ProjectMemoryResponse memory = service.get(actor, projectId);

        assertThat(memory.getContent()).isEmpty();
        assertThat(memory.getProjectId()).isEqualTo(projectId.toString());
        assertThat(Files.readString(root.resolve(projectId.toString()).resolve("memory.md"))).isEmpty();
        verify(access).requireProjectMember(projectId, actor);
    }

    @Test
    void saveWritesContentAndReturnsNewVersion() {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();
        String empty = service.get(actor, projectId).getVersion();

        ProjectMemoryResponse saved = service.save(actor, projectId, "# 登录约定\n- 统一邮箱登录", empty);

        assertThat(saved.getContent()).isEqualTo("# 登录约定\n- 统一邮箱登录");
        assertThat(saved.getVersion()).isNotEqualTo(empty);
        assertThat(service.get(actor, projectId).getVersion()).isEqualTo(saved.getVersion());
        verify(events).publish(eq(projectId), isNull(), eq("memory.updated"), eq(projectId.toString()), any());
    }

    @Test
    void saveRejectsStaleVersionAndKeepsCurrentContent() throws Exception {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();
        String version = service.get(actor, projectId).getVersion();
        service.save(actor, projectId, "第一版", version);

        ApiException error = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.save(actor, projectId, "第二版", version));

        assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error.code()).isEqualTo("MEMORY_VERSION_CONFLICT");
        assertThat(Files.readString(root.resolve(projectId.toString()).resolve("memory.md"), StandardCharsets.UTF_8))
                .isEqualTo("第一版");
    }

    @Test
    void saveRejectsContentOverContextBudget() {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();
        String version = service.get(actor, projectId).getVersion();

        ApiException error = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.save(actor, projectId, "x".repeat(MAX_CHARS + 1), version));

        assertThat(error.code()).isEqualTo("MEMORY_CONTEXT_LIMIT_EXCEEDED");
    }

    @Test
    void projectsKeepSeparateMemoryFiles() {
        Long actor = TestIds.next();
        Long first = TestIds.next();
        Long second = TestIds.next();

        service.save(actor, first, "项目一", service.get(actor, first).getVersion());

        assertThat(service.get(actor, second).getContent()).isEmpty();
    }

    @Test
    void saveRequiresProjectMembership() {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见"))
                .when(access).requireProjectMember(projectId, actor);

        assertThatThrownBy(() -> service.save(actor, projectId, "内容", "any"))
                .isInstanceOf(ApiException.class);
    }
}

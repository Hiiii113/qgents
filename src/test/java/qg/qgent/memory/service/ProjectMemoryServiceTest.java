package qg.qgent.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpStatus;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.common.web.ApiException;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.support.TestIds;

/** 项目 memory.md：首次创建、管理员写入、版本检测、默认群、字符预算、磁盘失败、项目隔离与权限校验。 */
class ProjectMemoryServiceTest {

    private static final int MAX_CHARS = 100;

    @TempDir Path root;

    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final ChatGroupMapper groups = mock(ChatGroupMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final EventService events = mock(EventService.class);
    private final Long group = TestIds.next();
    private ProjectMemoryService service;

    @BeforeEach
    void setUp() {
        service = service(root);
        when(groups.selectById(group)).thenReturn(group(group, true));
    }

    private ProjectMemoryService service(Path directory) {
        return new ProjectMemoryService(
                access,
                groups,
                projects,
                new ProjectDirectoryService(directory.toString(), directory.toString()),
                events,
                MAX_CHARS);
    }

    @Test
    void firstReadCreatesEmptyMemoryFileInProjectDirectory() throws Exception {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();

        ProjectMemoryResponse memory = service.get(actor, projectId);

        assertThat(memory.getContent()).isEmpty();
        assertThat(memory.getProjectId()).isEqualTo(projectId.toString());
        assertThat(Files.readString(root.resolve(projectId.toString()).resolve("memory.md")))
                .isEmpty();
        verify(access).requireProjectMember(projectId, actor);
    }

    @Test
    void adminWriteReplacesMemoryAndPublishesUpdate() {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();
        String empty = service.get(actor, projectId).getVersion();

        ProjectMemoryResponse saved =
                service.update(actor, projectId, group, "# 登录约定\n- 统一邮箱登录", empty);

        assertThat(saved.getContent()).isEqualTo("# 登录约定\n- 统一邮箱登录");
        assertThat(saved.getVersion()).isNotEqualTo(empty);
        assertThat(service.get(actor, projectId).getVersion()).isEqualTo(saved.getVersion());
        verify(access).requireProjectAdmin(projectId, actor);
        verify(projects).selectByIdForUpdate(projectId);
        verify(events)
                .publish(
                        eq(projectId),
                        isNull(),
                        eq("memory.updated"),
                        eq(projectId.toString()),
                        any());
    }

    @Test
    void staleBaseVersionIsRejectedAndKeepsCurrentContent() throws Exception {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();
        String version = service.get(actor, projectId).getVersion();
        service.update(actor, projectId, group, "第一版", version);

        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> service.update(actor, projectId, group, "第二版", version))
                .satisfies(error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.code()).isEqualTo("MEMORY_VERSION_CONFLICT");
                });
        assertThat(
                        Files.readString(
                                root.resolve(projectId.toString()).resolve("memory.md"),
                                StandardCharsets.UTF_8))
                .isEqualTo("第一版");
    }

    @Test
    void writingTheSameContentAgainIsIdempotent() {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();
        String version = service.get(actor, projectId).getVersion();
        ProjectMemoryResponse first = service.update(actor, projectId, group, "同一版", version);

        ProjectMemoryResponse again = service.update(actor, projectId, group, "同一版", version);

        assertThat(again.getVersion()).isEqualTo(first.getVersion());
    }

    @Test
    void contentOverContextBudgetIsRejectedBeforeItIsStored() {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();
        String version = service.get(actor, projectId).getVersion();

        assertThatThrownBy(() ->
                        service.update(actor, projectId, group, "x".repeat(MAX_CHARS + 1), version))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "MEMORY_CONTEXT_LIMIT_EXCEEDED");
        assertThat(service.get(actor, projectId).getContent()).isEmpty();
    }

    @Test
    void plainMemberCannotWriteAndMemoryStaysUnchanged() {
        Long projectId = TestIds.next();
        Long member = TestIds.next();
        String version = service.get(member, projectId).getVersion();
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限"))
                .when(access)
                .requireProjectAdmin(projectId, member);

        assertThatThrownBy(() -> service.update(member, projectId, group, "# 新约定", version))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "PROJECT_ADMIN_REQUIRED");

        assertThat(service.get(member, projectId).getContent()).isEmpty();
        verify(projects, never()).selectByIdForUpdate(any());
    }

    @Test
    void writeFromRestrictedGroupIsRefusedAndMemoryStaysUnchanged() {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();
        Long restricted = TestIds.next();
        when(groups.selectById(restricted)).thenReturn(group(restricted, false));
        String version = service.get(actor, projectId).getVersion();

        assertThatThrownBy(() -> service.update(actor, projectId, restricted, "# 受限群结论", version))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "MEMORY_SOURCE_GROUP_RESTRICTED");

        assertThat(service.get(actor, projectId).getContent()).isEmpty();
        verify(projects, never()).selectByIdForUpdate(any());
    }

    @Test
    void diskFailureIsRaisedWithoutPublishingAnUpdate() throws Exception {
        Path blocked = Files.createFile(root.resolve("blocked-root"));
        ProjectMemoryService broken = service(blocked);
        Long projectId = TestIds.next();
        Long actor = TestIds.next();

        assertThatThrownBy(() -> broken.update(actor, projectId, group, "# 新约定", "whatever"))
                .isInstanceOf(IllegalStateException.class);

        verify(events, never())
                .publish(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any());
    }

    @Test
    void projectsKeepSeparateMemoryFiles() {
        Long actor = TestIds.next();
        Long first = TestIds.next();
        Long second = TestIds.next();

        service.update(actor, first, group, "项目一", service.get(actor, first).getVersion());

        assertThat(service.get(actor, second).getContent()).isEmpty();
    }

    @Test
    void readRequiresProjectMembership() {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见"))
                .when(access)
                .requireProjectMember(projectId, actor);

        assertThatThrownBy(() -> service.get(actor, projectId)).isInstanceOf(ApiException.class);
    }

    private ChatGroupEntity group(Long groupId, boolean defaultGroup) {
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setDefaultGroup(defaultGroup);
        return group;
    }
}

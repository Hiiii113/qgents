package qg.qgent.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
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

/** 项目 memory.md：管理员写入、默认群、幂等、磁盘失败、项目隔离与权限校验。 */
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
                new MemoryDocument(MAX_CHARS));
    }

    @Test
    void firstReadIsEmptyWithoutCreatingMemoryFile() {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();

        ProjectMemoryResponse memory = service.get(actor, projectId);

        assertThat(memory.getContent()).isEmpty();
        assertThat(memory.getProjectId()).isEqualTo(projectId.toString());
        assertThat(memory.getUpdatedAt()).isNull();
        assertThat(root.resolve(projectId.toString()).resolve("memory.md")).doesNotExist();
        verify(access).requireProjectMember(projectId, actor);
    }

    @Test
    void adminWriteReplacesMemoryAndPublishesUpdate() {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();

        ProjectMemoryResponse saved = service.update(actor, projectId, group, "# 登录约定\n- 统一邮箱登录");

        assertThat(saved.getContent()).isEqualTo("# 登录约定\n- 统一邮箱登录");
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(service.get(actor, projectId).getContent()).isEqualTo("# 登录约定\n- 统一邮箱登录");
        verify(access).requireProjectAdmin(projectId, actor);
        verify(projects).selectByIdForUpdate(projectId);
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events)
                .publish(
                        eq(projectId),
                        isNull(),
                        eq("memory.updated"),
                        eq(projectId.toString()),
                        payload.capture());
        assertThat(payload.getValue()).containsOnlyKeys("projectId", "updatedAt");
    }

    @Test
    void writingTheSameContentAgainIsIdempotent() {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();
        ProjectMemoryResponse first = service.update(actor, projectId, group, "同一版");

        ProjectMemoryResponse again = service.update(actor, projectId, group, "同一版");

        assertThat(again.getContent()).isEqualTo(first.getContent());
        assertThat(again.getUpdatedAt()).isEqualTo(first.getUpdatedAt());
        verify(events, times(1)).publish(any(), any(), any(), any(), any());
    }

    @Test
    void plainMemberCannotWriteAndMemoryStaysUnchanged() {
        Long projectId = TestIds.next();
        Long member = TestIds.next();
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限"))
                .when(access)
                .requireProjectAdmin(projectId, member);

        assertThatThrownBy(() -> service.update(member, projectId, group, "# 新约定"))
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

        assertThatThrownBy(() -> service.update(actor, projectId, restricted, "# 受限群结论"))
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

        assertThatThrownBy(() -> broken.update(actor, projectId, group, "# 新约定"))
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

        service.update(actor, first, group, "项目一");

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

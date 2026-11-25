package qg.qgent.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import qg.qgent.chat.service.GroupService;
import qg.qgent.common.web.ApiException;
import qg.qgent.memory.dto.GroupMemoryResponse;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.support.TestIds;

/** 群记忆文件：按项目和群隔离、revision 乐观锁、读写入口的权限校验。 */
class GroupMemoryServiceTest {

    private static final int MAX_CHARS = 200;

    @TempDir Path root;

    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final GroupService groups = mock(GroupService.class);
    private GroupMemoryService service;

    @BeforeEach
    void setUp() {
        service =
                new GroupMemoryService(
                        access,
                        groups,
                        new ProjectDirectoryService(root.toString(), root.toString()),
                        new MemoryDocument(MAX_CHARS),
                        new GroupMemoryMetaStore());
    }

    @Test
    void groupsAndProjectsKeepSeparateMemoryFiles() throws Exception {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        Long otherGroup = TestIds.next();
        Long otherProject = TestIds.next();

        service.writeByAgent(projectId, groupId, "# 登录改造\n- 结论：先做邮箱登录");

        assertThat(service.read(projectId, groupId).getContent()).isEqualTo("# 登录改造\n- 结论：先做邮箱登录");
        assertThat(
                        Files.readString(
                                root.resolve(projectId.toString())
                                        .resolve("groups")
                                        .resolve(groupId.toString())
                                        .resolve("memory.md"),
                                StandardCharsets.UTF_8))
                .startsWith("# 登录改造");
        assertThat(service.read(projectId, otherGroup).getContent()).isEmpty();
        assertThat(service.read(otherProject, groupId).getContent()).isEmpty();
    }

    @Test
    void unrefinedGroupReadsEmptyMemoryWithoutCreatingFile() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();

        GroupMemoryResponse memory = service.read(projectId, groupId);

        assertThat(memory.getContent()).isEmpty();
        assertThat(memory.getUpdatedAt()).isNull();
        assertThat(memory.getGroupId()).isEqualTo(groupId.toString());
        assertThat(
                        root.resolve(projectId.toString())
                                .resolve("groups")
                                .resolve(groupId.toString())
                                .resolve("memory.md"))
                .doesNotExist();
    }

    @Test
    void snapshotAndMetaStartEmptyAndMoveTogetherWithEachWrite() throws Exception {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();

        assertThat(service.meta(projectId, groupId)).isEqualTo(GroupMemoryMeta.initial());
        assertThat(service.readWithRevision(projectId, groupId).revision()).isZero();
        assertThat(metaFile(projectId, groupId)).doesNotExist();

        assertThat(service.writeByAgent(projectId, groupId, "# 群记忆")).isEqualTo(1);
        GroupMemorySnapshot first = service.readWithRevision(projectId, groupId);
        assertThat(first.content()).isEqualTo("# 群记忆");
        assertThat(first.revision()).isEqualTo(1);

        assertThat(service.writeByAgent(projectId, groupId, "# 群记忆\n- 补充")).isEqualTo(2);
        assertThat(service.readWithRevision(projectId, groupId).revision()).isEqualTo(2);
        assertThat(Files.readString(metaFile(projectId, groupId), StandardCharsets.UTF_8))
                .contains("\"revision\":2");
    }

    @Test
    void editReplacesUniqueAnchorAndBumpsRevision() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        service.writeByAgent(projectId, groupId, "## 群内决策与规范\n- [2026-08-01] 先做邮箱登录");

        long revision =
                service.edit(projectId, groupId, "先做邮箱登录", "改用手机号登录");

        assertThat(revision).isEqualTo(2);
        assertThat(service.read(projectId, groupId).getContent())
                .isEqualTo("## 群内决策与规范\n- [2026-08-01] 改用手机号登录");
    }

    @Test
    void editRejectsMissingOrAmbiguousAnchorWithoutChangingRevision() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        service.writeByAgent(projectId, groupId, "- 结论：A\n- 结论：A");

        assertThatThrownBy(() -> service.edit(projectId, groupId, "结论：A", "结论：B"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("MEMORY_EDIT_ANCHOR_AMBIGUOUS");
        assertThatThrownBy(() -> service.edit(projectId, groupId, "结论：C", "结论：B"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("MEMORY_EDIT_ANCHOR_NOT_FOUND");
        assertThat(service.readWithRevision(projectId, groupId).revision()).isEqualTo(1);
    }

    @Test
    void writeByAgentRejectsContentBeyondBudgetWithoutChangingRevision() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();

        assertThatThrownBy(() -> service.writeByAgent(projectId, groupId, "x".repeat(MAX_CHARS + 1)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("MEMORY_CONTEXT_LIMIT_EXCEEDED");
        assertThat(service.readWithRevision(projectId, groupId).revision()).isZero();
    }

    @Test
    void extractionWritesOnlyWhenTheRevisionItReadIsStillCurrent() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        service.writeByAgent(projectId, groupId, "# 旧群记忆");
        long revision = service.readWithRevision(projectId, groupId).revision();

        assertThat(
                        service.writeIfRevisionMatches(
                                projectId, groupId, revision, "# 提炼结果", 7))
                .isTrue();
        assertThat(service.read(projectId, groupId).getContent()).isEqualTo("# 提炼结果");
        GroupMemoryMeta meta = service.meta(projectId, groupId);
        assertThat(meta.revision()).isEqualTo(revision + 1);
        assertThat(meta.lastProcessedSequence()).isEqualTo(7);
    }

    @Test
    void extractionDiscardsItsResultWhenAgentWroteDuringExtraction() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        service.writeByAgent(projectId, groupId, "# 旧群记忆");
        GroupMemorySnapshot read = service.readWithRevision(projectId, groupId);

        // 提炼调用模型期间，Agent 直接修正了群记忆
        service.edit(projectId, groupId, "旧群记忆", "Agent 修正后的群记忆");

        assertThat(
                        service.writeIfRevisionMatches(
                                projectId, groupId, read.revision(), "# 提炼结果", 9))
                .isFalse();
        assertThat(service.read(projectId, groupId).getContent()).isEqualTo("# Agent 修正后的群记忆");
        assertThat(service.meta(projectId, groupId).lastProcessedSequence()).isZero();
    }

    @Test
    void discoveryBookkeepingKeepsRevisionAndWatermark() throws Exception {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        service.writeByAgent(projectId, groupId, "# 群记忆");
        long revision = service.readWithRevision(projectId, groupId).revision();
        Instant at = Instant.parse("2026-08-18T10:00:00Z");

        service.markDiscovered(projectId, groupId, "v1", at);

        GroupMemoryMeta meta = service.meta(projectId, groupId);
        assertThat(meta.revision()).isEqualTo(revision);
        assertThat(meta.discoveredVersion()).isEqualTo("v1");
        assertThat(meta.lastDiscoveredAt()).isEqualTo(at);
        assertThat(Files.readString(metaFile(projectId, groupId), StandardCharsets.UTF_8))
                .contains("2026-08-18T10:00:00Z");
    }

    @Test
    void adminReadRequiresProjectAdminThenGroupMembership() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        Long actor = TestIds.next();
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限"))
                .when(access)
                .requireProjectAdmin(projectId, actor);

        assertThatThrownBy(() -> service.get(actor, projectId, groupId))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("PROJECT_ADMIN_REQUIRED");
        verify(groups, never()).requireChatGroup(projectId, groupId, actor);
    }

    @Test
    void adminOutsideSourceGroupCannotReadGroupMemory() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        Long actor = TestIds.next();
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问"))
                .when(groups)
                .requireChatGroup(projectId, groupId, actor);

        assertThatThrownBy(() -> service.get(actor, projectId, groupId))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("GROUP_NOT_FOUND");
    }

    @Test
    void adminGroupMemberReadsCurrentContent() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        Long actor = TestIds.next();
        service.writeByAgent(projectId, groupId, "# 群记忆");

        GroupMemoryResponse memory = service.get(actor, projectId, groupId);

        assertThat(memory.getContent()).isEqualTo("# 群记忆");
        assertThat(memory.getProjectId()).isEqualTo(projectId.toString());
        verify(access).requireProjectAdmin(projectId, actor);
        verify(groups).requireChatGroup(projectId, groupId, actor);
    }

    private Path metaFile(Long projectId, Long groupId) {
        return root.resolve(projectId.toString())
                .resolve("groups")
                .resolve(groupId.toString())
                .resolve("memory-meta.json");
    }
}

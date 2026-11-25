package qg.qgent.skill.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.skill.dto.SkillUpdateRequest;
import qg.qgent.skill.entity.SkillEntity;
import qg.qgent.skill.mapper.SkillMapper;
import qg.qgent.support.TestIds;
import qg.qgent.user.mapper.UserMapper;

/** 项目 Skill 的创建上架、编辑、审核与可见性。 */
class SkillServiceTest {
    private final SkillMapper mapper = mock(SkillMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final EventService events = mock(EventService.class);
    private final SkillService service =
            new SkillService(mapper, access, mock(UserMapper.class), events);
    private final Long projectId = TestIds.next(), owner = TestIds.next(), other = TestIds.next();

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PENDING_REVIEW", "REJECTED", "PUBLISHED"})
    void ownerCanEditAllActiveStatesWithoutChangingPublicationStatus(String status) {
        SkillEntity skill = skill(status, "PRIVATE");
        SkillUpdateRequest request = new SkillUpdateRequest();
        String content = "---\nname: revised\ndescription: 更新后的用途\n---\n 新内容 \n";
        request.setContent(content);

        var result = service.update(owner, projectId, skill.getId(), request);

        assertThat(result.getContent()).isEqualTo(content);
        assertThat(result.getName()).isEqualTo("revised");
        assertThat(result.getDescription()).isEqualTo("更新后的用途");
        assertThat(result.getStatus()).isEqualTo(status);
        verify(mapper).updateById(skill);
        verify(events)
                .publish(
                        eq(projectId),
                        isNull(),
                        eq("skill.updated"),
                        eq(skill.getId().toString()),
                        any());
    }

    @Test
    void editingTagsDoesNotChangeFileOrMetadata() {
        SkillEntity skill = skill("PUBLISHED", "PRIVATE");
        skill.setDescription("用途");
        SkillUpdateRequest request = new SkillUpdateRequest();
        request.setTags(java.util.List.of("tag"));
        var result = service.update(owner, projectId, skill.getId(), request);
        assertThat(result.getName()).isEqualTo("规范");
        assertThat(result.getDescription()).isEqualTo("用途");
        assertThat(result.getContent()).isEqualTo("内容");
        assertThat(result.getTags()).containsExactly("tag");
    }

    @Test
    void rejectedSkillCanBeEditedAndResubmitted() {
        SkillEntity skill = skill("REJECTED", "PROJECT_SHARED");
        SkillUpdateRequest request = new SkillUpdateRequest();
        request.setContent("---\nname: revised\ndescription: 修订说明\n---\n修订内容");
        service.update(owner, projectId, skill.getId(), request);
        assertThat(service.submitReview(owner, projectId, skill.getId()).getStatus())
                .isEqualTo("PENDING_REVIEW");
    }

    @Test
    void ownerCanArchivePrivatePublishedSkillWithoutAdminPermission() {
        SkillEntity skill = skill("PUBLISHED", "PRIVATE");
        assertThat(service.archive(owner, projectId, skill.getId()).getStatus())
                .isEqualTo("ARCHIVED");
        verify(access, never()).requireProjectAdmin(any(), any());
    }

    @Test
    void adminCanEditAndArchiveSharedPublishedSkill() {
        SkillEntity skill = skill("PUBLISHED", "PROJECT_SHARED");
        SkillUpdateRequest request = new SkillUpdateRequest();
        request.setContent("---\nname: revised\ndescription: 新用途\n---\n新内容");
        service.update(other, projectId, skill.getId(), request);
        service.archive(other, projectId, skill.getId());
        verify(access, times(2)).requireProjectAdmin(projectId, other);
    }

    @Test
    void ordinaryNonOwnerCannotEditOrArchive() {
        SkillEntity skill = skill("PUBLISHED", "PROJECT_SHARED");
        doThrow(
                        new ApiException(
                                org.springframework.http.HttpStatus.FORBIDDEN,
                                "PROJECT_ADMIN_REQUIRED",
                                "需要管理员"))
                .when(access)
                .requireProjectAdmin(projectId, other);
        assertThatThrownBy(() ->
                        service.update(other, projectId, skill.getId(), new SkillUpdateRequest()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.archive(other, projectId, skill.getId()))
                .isInstanceOf(ApiException.class);
        verify(mapper, never()).updateById(any(SkillEntity.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PENDING_REVIEW", "REJECTED", "ARCHIVED"})
    void unpublishedSharedDetailsAreVisibleToOwnerAndAdminOnly(String status) {
        SkillEntity skill = skill(status, "PROJECT_SHARED");
        assertThat(service.get(owner, projectId, skill.getId()).getStatus()).isEqualTo(status);
        assertThatThrownBy(() -> service.get(other, projectId, skill.getId()))
                .isInstanceOf(ApiException.class);
        when(access.isProjectAdmin(projectId, other)).thenReturn(true);
        assertThat(service.get(other, projectId, skill.getId()).getStatus()).isEqualTo(status);
    }

    @Test
    void privateDetailsStayHiddenFromOtherUsersIncludingAdmins() {
        SkillEntity skill = skill("PUBLISHED", "PRIVATE");
        when(access.isProjectAdmin(projectId, other)).thenReturn(true);
        assertThatThrownBy(() -> service.get(other, projectId, skill.getId()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void archivedSkillAndBlankContentCannotBeEdited() {
        SkillEntity skill = skill("ARCHIVED", "PRIVATE");
        assertThatThrownBy(() ->
                        service.update(owner, projectId, skill.getId(), new SkillUpdateRequest()))
                .isInstanceOf(ApiException.class);
        skill.setStatus("PUBLISHED");
        SkillUpdateRequest request = new SkillUpdateRequest();
        request.setContent(" ");
        assertThatThrownBy(() -> service.update(owner, projectId, skill.getId(), request))
                .isInstanceOf(ApiException.class);
        verify(mapper, never()).updateById(any(SkillEntity.class));
    }

    @Test
    void previewChecksMembershipAndDoesNotSave() throws Exception {
        String content = "---\nname: example\ndescription: 用途\n---\n正文\n";
        var file =
                new MockMultipartFile(
                        "file",
                        "SKILL.md",
                        "text/markdown",
                        content.getBytes(StandardCharsets.UTF_8));
        var preview = service.preview(owner, projectId, file);
        assertThat(preview.name()).isEqualTo("example");
        assertThat(preview.description()).isEqualTo("用途");
        assertThat(preview.content()).isEqualTo(content);
        verify(access).requireProjectMember(projectId, owner);
        verify(mapper, never()).insert(any(SkillEntity.class));
    }

    @Test
    void listPassesAdminVisibilityAndNormalizedFilters() {
        when(access.isProjectAdmin(projectId, other)).thenReturn(true);
        service.list(other, projectId, " DRAFT ", " tag ");
        verify(mapper).listSkills(projectId, other, "DRAFT", "tag", true);
        service.list(owner, projectId, null, null);
        verify(mapper).listSkills(projectId, owner, null, null, false);
    }

    @Test
    void skillCreatedByAdminIsPublishedImmediately() throws Exception {
        when(access.isProjectAdmin(projectId, owner)).thenReturn(true);
        givenCreatedSkillIsReadBack();

        var result =
                service.create(
                        owner, projectId, skillFile("规范"), "PROJECT_SHARED", java.util.List.of());

        assertThat(result.getStatus()).isEqualTo("PUBLISHED");
        assertThat(result.getVisibility()).isEqualTo("PROJECT_SHARED");
        verify(events).publish(eq(projectId), isNull(), eq("skill.published"), any(), any());
    }

    @Test
    void skillCreatedByMemberStaysDraftWhenProjectShared() throws Exception {
        when(access.isProjectAdmin(projectId, other)).thenReturn(false);
        SkillEntity draft = skill("DRAFT", "PROJECT_SHARED");
        when(mapper.selectById(any())).thenReturn(draft);

        var result =
                service.create(
                        other, projectId, skillFile("规范"), "PROJECT_SHARED", java.util.List.of());

        assertThat(result.getStatus()).isEqualTo("DRAFT");
        verify(events, never()).publish(any(), any(), eq("skill.published"), any(), any());
    }

    @Test
    void privateSkillCreatedByMemberIsPublishedImmediately() throws Exception {
        when(access.isProjectAdmin(projectId, other)).thenReturn(false);
        givenCreatedSkillIsReadBack();

        var result =
                service.create(
                        other, projectId, skillFile("个人工具"), "PRIVATE", java.util.List.of());

        assertThat(result.getStatus()).isEqualTo("PUBLISHED");
        assertThat(result.getVisibility()).isEqualTo("PRIVATE");
        verify(events).publish(eq(projectId), isNull(), eq("skill.published"), any(), any());
    }

    private void givenCreatedSkillIsReadBack() {
        final SkillEntity[] created = new SkillEntity[1];
        when(mapper.insert(any(SkillEntity.class)))
                .thenAnswer(
                        invocation -> {
                            created[0] = invocation.getArgument(0);
                            created[0].setId(TestIds.next());
                            return 1;
                        });
        when(mapper.selectById(any())).thenAnswer(invocation -> created[0]);
    }

    private MockMultipartFile skillFile(String name) {
        String content = "---\nname: " + name + "\ndescription: 测试用途\n---\n内容";
        return new MockMultipartFile(
                "file", "SKILL.md", "text/markdown", content.getBytes(StandardCharsets.UTF_8));
    }

    private SkillEntity skill(String status, String visibility) {
        SkillEntity skill = new SkillEntity();
        skill.setId(TestIds.next());
        skill.setProjectId(projectId);
        skill.setCreatedBy(owner);
        skill.setName("规范");
        skill.setContent("内容");
        skill.setStatus(status);
        skill.setVisibility(visibility);
        when(mapper.selectById(skill.getId())).thenReturn(skill);
        return skill;
    }
}

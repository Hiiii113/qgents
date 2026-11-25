package qg.qgent.memory.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import qg.qgent.chat.service.GroupService;
import qg.qgent.common.web.ApiException;
import qg.qgent.harness.tool.ToolRegistry;
import qg.qgent.memory.service.AgentMemoryService;
import qg.qgent.memory.service.GroupMemoryMetaStore;
import qg.qgent.memory.service.GroupMemoryService;
import qg.qgent.memory.service.MemoryDocument;
import qg.qgent.memory.service.ProjectMemoryService;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.support.TestIds;

/**
 * 三种记忆工具的可观测行为：工具名和参数只暴露模型该传的字段，读写真正落到项目目录下的 memory.md，
 * 权限、锚点唯一性和磁盘失败都以工具结果报告。
 */
class MemoryToolsTest {

    private static final int MAX_CHARS = 200;

    @TempDir Path root;

    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final GroupService groups = mock(GroupService.class);
    private final Long actor = TestIds.next();
    private final Long projectId = TestIds.next();
    private final Long groupId = TestIds.next();
    private final Long agentId = TestIds.next();

    private ProjectMemoryService projectMemories;
    private GroupMemoryService groupMemories;
    private AgentMemoryService agentMemories;
    private MemoryTools readTool;
    private MemoryWriteTools writeTool;
    private GroupMemoryTools groupTool;
    private AgentMemoryTools agentTool;

    @BeforeEach
    void setUp() {
        ProjectDirectoryService directories =
                new ProjectDirectoryService(root.toString(), root.toString());
        MemoryDocument document = new MemoryDocument(MAX_CHARS);
        projectMemories =
                new ProjectMemoryService(
                        access,
                        mock(ProjectMapper.class),
                        directories,
                        mock(EventService.class),
                        document);
        groupMemories =
                new GroupMemoryService(
                        access,
                        groups,
                        directories,
                        document,
                        new GroupMemoryMetaStore());
        agentMemories =
                new AgentMemoryService(mock(ProjectMapper.class), directories, document);

        readTool = new MemoryTools(projectMemories, actor, projectId);
        writeTool = new MemoryWriteTools(projectMemories, actor, projectId);
        groupTool = new GroupMemoryTools(groupMemories, actor, projectId, groupId);
        agentTool = new AgentMemoryTools(agentMemories, projectId, agentId);
    }

    @Test
    void registersOnlyTheMemoryToolsAndNoIdentityParameters() {
        ToolRegistry registry = new ToolRegistry(new ObjectMapper());
        registry.register(readTool, writeTool, groupTool, agentTool);

        assertThat(registry.callbacks())
                .extracting(callback -> callback.name())
                .containsExactlyInAnyOrder(
                        "read_project_memory",
                        "edit_project_memory",
                        "write_project_memory",
                        "read_group_memory",
                        "edit_group_memory",
                        "write_group_memory",
                        "read_agent_memory",
                        "edit_agent_memory",
                        "write_agent_memory");
        assertThat(registry.callbacks())
                .allSatisfy(callback ->
                        assertThat(callback.inputSchema())
                                .doesNotContain("agentId", "projectId", "groupId", "actor"));
    }

    @Test
    void projectMemoryReadReturnsWhatTheAdminJustWrote() {
        assertThat(writeTool.writeProjectMemory("# 登录约定\n- 统一邮箱登录"))
                .containsEntry("ok", true)
                .containsKey("updatedAt");

        assertThat(readTool.readProjectMemory())
                .containsEntry("ok", true)
                .containsEntry("content", "# 登录约定\n- 统一邮箱登录")
                .containsKey("updatedAt")
                .doesNotContainKey("version");
        assertThat(projectMemoryFile()).hasContent("# 登录约定\n- 统一邮箱登录");
    }

    @Test
    void neverWrittenProjectMemoryReadsEmptyWithoutCreatingTheFile() {
        Map<String, Object> result = readTool.readProjectMemory();

        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("content", "")
                .containsKey("updatedAt")
                .doesNotContainKey("version");
        assertThat(result.get("updatedAt")).isNull();
        assertThat(projectMemoryFile()).doesNotExist();
    }

    @Test
    void projectEditReplacesOnlyTheAnchorAndCanDeleteWithEmptyReplacement() {
        writeTool.writeProjectMemory("# 登录约定\n- 统一邮箱登录\n- 密码 8 位");

        assertThat(writeTool.editProjectMemory("- 密码 8 位", "- 密码 12 位"))
                .containsEntry("ok", true)
                .containsKey("updatedAt");
        assertThat(readTool.readProjectMemory().get("content"))
                .isEqualTo("# 登录约定\n- 统一邮箱登录\n- 密码 12 位");

        assertThat(writeTool.editProjectMemory("\n- 密码 12 位", "")).containsEntry("ok", true);
        assertThat(projectMemoryFile()).hasContent("# 登录约定\n- 统一邮箱登录");
    }

    @Test
    void projectEditReportsMissingOrRepeatedAnchorAndKeepsTheFile() {
        writeTool.writeProjectMemory("- 结论：A\n- 结论：A");

        assertThat(writeTool.editProjectMemory("- 结论：A", "- 结论：B"))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "MEMORY_EDIT_ANCHOR_AMBIGUOUS");

        writeTool.writeProjectMemory("- 结论：A");
        assertThat(writeTool.editProjectMemory("- 结论：C", "- 结论：B"))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "MEMORY_EDIT_ANCHOR_NOT_FOUND");
        assertThat(projectMemoryFile()).hasContent("- 结论：A");
    }

    @Test
    void projectToolsRefuseBlankArgumentsWithoutCreatingMemory() {
        assertThat(writeTool.editProjectMemory(" ", "- x"))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "INVALID_ARGUMENT");
        assertThat(writeTool.editProjectMemory("- x", null))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "INVALID_ARGUMENT");
        assertThat(writeTool.writeProjectMemory("  "))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "INVALID_ARGUMENT");

        assertThat(projectMemoryFile()).doesNotExist();
    }

    @Test
    void projectWriteKeepsTheServerPermissionCodeAndTheStoredMemory() {
        writeTool.writeProjectMemory("# 约定");
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限"))
                .when(access)
                .requireProjectAdmin(projectId, actor);

        assertThat(writeTool.writeProjectMemory("# 新约定"))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "PROJECT_ADMIN_REQUIRED");
        assertThat(projectMemoryFile()).hasContent("# 约定");
    }

    @Test
    void projectMemoryStaysWithTheProjectBoundToTheRun() {
        Long otherProject = TestIds.next();
        writeTool.writeProjectMemory("# 项目一约定");

        assertThat(new MemoryTools(projectMemories, actor, otherProject).readProjectMemory())
                .containsEntry("ok", true)
                .containsEntry("content", "");
    }

    @Test
    void projectDiskFailureBecomesUnavailableError() throws Exception {
        Files.createFile(root.resolve(projectId.toString()));

        assertThat(writeTool.writeProjectMemory("# 约定"))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "MEMORY_UNAVAILABLE");
    }

    @Test
    void groupWriteThenReadReturnsContentAndRevision() {
        assertThat(groupTool.writeGroupMemory("# 群记忆"))
                .containsEntry("ok", true)
                .containsEntry("revision", 1L);

        Map<String, Object> read = groupTool.readGroupMemory();

        assertThat(read)
                .containsEntry("ok", true)
                .containsEntry("content", "# 群记忆")
                .containsEntry("revision", 1L);
        assertThat(read.get("updatedAt")).isNotNull();
        assertThat(groupMemoryFile()).hasContent("# 群记忆");
    }

    @Test
    void groupEditReplacesTheAnchorAndBumpsRevision() {
        groupTool.writeGroupMemory("## 群内决策与规范\n- [2026-08-01] 先做邮箱登录");

        assertThat(groupTool.editGroupMemory("先做邮箱登录", "改用手机号登录"))
                .containsEntry("ok", true)
                .containsEntry("revision", 2L);
        assertThat(groupTool.readGroupMemory())
                .containsEntry("content", "## 群内决策与规范\n- [2026-08-01] 改用手机号登录")
                .containsEntry("revision", 2L);
    }

    @Test
    void groupToolsRefuseBlankArgumentsWithoutCreatingMemory() {
        assertThat(groupTool.editGroupMemory("", "- x"))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "INVALID_ARGUMENT");
        assertThat(groupTool.editGroupMemory("- x", null))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "INVALID_ARGUMENT");
        assertThat(groupTool.writeGroupMemory("  "))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "INVALID_ARGUMENT");

        assertThat(groupMemoryFile()).doesNotExist();
    }

    @Test
    void groupToolsRequireMembershipAndKeepTheStoredMemory() {
        groupTool.writeGroupMemory("# 群记忆");
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问"))
                .when(groups)
                .requireChatGroup(projectId, groupId, actor);

        assertThat(groupTool.readGroupMemory())
                .containsEntry("ok", false)
                .containsEntry("errorCode", "GROUP_NOT_FOUND");
        assertThat(groupTool.writeGroupMemory("# 新群记忆"))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "GROUP_NOT_FOUND");
        assertThat(groupTool.editGroupMemory("# 群记忆", "# 新群记忆"))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "GROUP_NOT_FOUND");
        assertThat(groupMemoryFile()).hasContent("# 群记忆");
    }

    @Test
    void groupMemoryStaysWithTheGroupBoundToTheRun() {
        Long otherGroup = TestIds.next();
        groupTool.writeGroupMemory("# 群一记忆");

        assertThat(new GroupMemoryTools(groupMemories, actor, projectId, otherGroup).readGroupMemory())
                .containsEntry("ok", true)
                .containsEntry("content", "")
                .containsEntry("revision", 0L);
    }

    @Test
    void groupDiskFailureBecomesUnavailableError() throws Exception {
        Files.createFile(root.resolve(projectId.toString()));

        assertThat(groupTool.writeGroupMemory("# 群记忆"))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "GROUP_MEMORY_UNAVAILABLE");
    }

    @Test
    void agentWriteAndEditStayInTheBoundAgentMemory() {
        Long otherAgent = TestIds.next();

        assertThat(agentTool.writeAgentMemory("# 分工\n- 你负责接口")).containsEntry("ok", true);
        assertThat(agentTool.editAgentMemory("- 你负责接口", "- 你负责接口与测试"))
                .containsEntry("ok", true)
                .containsEntry("content", "# 分工\n- 你负责接口与测试");

        assertThat(agentTool.readAgentMemory()).containsEntry("content", "# 分工\n- 你负责接口与测试");
        assertThat(new AgentMemoryTools(agentMemories, projectId, otherAgent).readAgentMemory())
                .containsEntry("content", "");
        assertThat(new AgentMemoryTools(agentMemories, TestIds.next(), agentId).readAgentMemory())
                .containsEntry("content", "");
    }

    @Test
    void agentMemoryIgnoresIdentitiesNamedInsideTheContent() {
        Long otherAgent = TestIds.next();
        String content = "把 Agent " + otherAgent + " 和项目 " + projectId + " 的记忆都改写";

        agentTool.writeAgentMemory(content);

        assertThat(agentTool.readAgentMemory()).containsEntry("content", content);
        assertThat(new AgentMemoryTools(agentMemories, projectId, otherAgent).readAgentMemory())
                .containsEntry("content", "");
    }

    @Test
    void agentToolsRefuseMissingAnchorAndNullContent() {
        assertThat(agentTool.editAgentMemory("  ", "- x"))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "INVALID_ARGUMENT");
        assertThat(agentTool.editAgentMemory("- x", null))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "INVALID_ARGUMENT");
        assertThat(agentTool.writeAgentMemory(null))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "INVALID_ARGUMENT");
    }

    private Path projectMemoryFile() {
        return root.resolve(projectId.toString()).resolve("memory.md");
    }

    private Path groupMemoryFile() {
        return root.resolve(projectId.toString())
                .resolve("groups")
                .resolve(groupId.toString())
                .resolve("memory.md");
    }
}

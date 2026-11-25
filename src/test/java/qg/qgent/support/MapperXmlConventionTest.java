package qg.qgent.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.attachment.mapper.AttachmentMapper;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.DirectConversationMapper;
import qg.qgent.chat.mapper.GroupAgentMapper;
import qg.qgent.chat.mapper.GroupReadStateMapper;
import qg.qgent.chat.mapper.GroupUserMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.github.mapper.GitHubInstallationMapper;
import qg.qgent.github.mapper.GitHubInstallationStateMapper;
import qg.qgent.github.mapper.ProjectRepositoryMapper;
import qg.qgent.harness.mapper.AgentRunMapper;
import qg.qgent.harness.mapper.AgentRunToolMapper;
import qg.qgent.mcp.mapper.McpMapper;
import qg.qgent.memory.mapper.GroupMemoryStateMapper;
import qg.qgent.notification.mapper.NotificationMapper;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.sandbox.execution.mapper.ToolExecutionLogMapper;
import qg.qgent.sandbox.execution.mapper.ToolExecutionMapper;
import qg.qgent.skill.mapper.SkillMapper;
import qg.qgent.team.mapper.TeamInvitationMapper;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.team.mapper.TeamMemberMapper;
import qg.qgent.user.mapper.UserGroupPreferenceMapper;
import qg.qgent.user.mapper.UserMapper;
import qg.qgent.workspace.mapper.WorkspaceMapper;

/**
 * Mapper 层统一约束：XML 全部可加载，接口不再出现注解 SQL 或手工结果映射。
 */
class MapperXmlConventionTest {

    private static final List<Class<?>> MAPPERS = List.of(
        AgentMapper.class,
        AttachmentMapper.class,
        ChatGroupMapper.class,
        DirectConversationMapper.class,
        GroupAgentMapper.class,
        GroupReadStateMapper.class,
        GroupUserMapper.class,
        MessageMapper.class,
        GitHubInstallationMapper.class,
        GitHubInstallationStateMapper.class,
        ProjectRepositoryMapper.class,
        AgentRunMapper.class,
        AgentRunToolMapper.class,
        McpMapper.class,
        GroupMemoryStateMapper.class,
        NotificationMapper.class,
        ProjectMapper.class,
        ProjectMemberMapper.class,
        ToolExecutionLogMapper.class,
        ToolExecutionMapper.class,
        SkillMapper.class,
        TeamInvitationMapper.class,
        TeamMapper.class,
        TeamMemberMapper.class,
        UserGroupPreferenceMapper.class,
        UserMapper.class,
        WorkspaceMapper.class
    );

    @Test
    void allMapperXmlsLoadAndInterfacesUseNoAnnotationSql() {
        var configuration =
            MapperTestSupport.configuration(MAPPERS.toArray(Class<?>[]::new));

        assertThat(configuration.getMappedStatements()).isNotEmpty();
        for (var mapper : MAPPERS) {
            for (var method : mapper.getDeclaredMethods()) {
                assertThat(method.getAnnotations())
                    .as("%s.%s", mapper.getSimpleName(), method.getName())
                    .noneMatch(annotation ->
                        annotation instanceof Select
                            || annotation instanceof Update
                            || annotation instanceof Insert
                            || annotation instanceof Delete
                            || annotation instanceof Results
                            || annotation instanceof Result);
                if (!method.isDefault() && !method.isSynthetic()) {
                    assertThat(configuration.hasStatement(mapper.getName() + "." + method.getName()))
                        .as("%s.%s 应有对应的 Mapper XML 语句", mapper.getSimpleName(), method.getName())
                        .isTrue();
                }
            }
        }
    }
}

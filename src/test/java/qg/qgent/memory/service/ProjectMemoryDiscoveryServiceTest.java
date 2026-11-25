package qg.qgent.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.agent.service.AgentService;
import qg.qgent.attachment.service.AttachmentService;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.DirectConversationMapper;
import qg.qgent.chat.mapper.GroupAgentMapper;
import qg.qgent.chat.mapper.GroupReadStateMapper;
import qg.qgent.chat.mapper.GroupUserMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.service.GroupService;
import qg.qgent.chat.service.MessageService;
import qg.qgent.chat.service.MessageViews;
import qg.qgent.harness.model.ModelClient;
import qg.qgent.memory.config.ProjectMemoryDiscoveryProperties;
import qg.qgent.notification.service.NotificationService;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.support.TestIds;
import qg.qgent.user.mapper.UserGroupPreferenceMapper;
import qg.qgent.user.mapper.UserMapper;

/** 项目记忆发现：只看默认群、按 memory-meta.json 跳过未变更版本、以普通消息发出建议。 */
class ProjectMemoryDiscoveryServiceTest {

    private static final int MAX_CHARS = 2000;
    private static final int INTERVAL_MINUTES = 1440;
    private static final int RETRY_DELAY_MINUTES = 60;
    private static final int MAX_ATTEMPTS = 3;
    private static final int BATCH_SIZE = 10;

    @TempDir Path root;

    private final ChatGroupMapper chatGroups = mock(ChatGroupMapper.class);
    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final GroupAgentMapper groupAgents = mock(GroupAgentMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ModelClient model = mock(ModelClient.class);
    private final MemoryDocument document = new MemoryDocument(MAX_CHARS);

    private GroupMemoryService groupMemories;
    private ProjectMemoryService projectMemories;
    private MessageService chat;
    private AgentService agents;
    private ProjectMemoryDiscoveryService service;

    @BeforeEach
    void setUp() {
        initializeMybatisMetadata();
        ProjectDirectoryService directories =
                new ProjectDirectoryService(root.toString(), root.toString());
        EventService events = mock(EventService.class);
        agents = new AgentService(agentMapper);
        GroupService chatGroupsService =
                new GroupService(
                        chatGroups,
                        mock(ProjectMemberMapper.class),
                        mock(GroupUserMapper.class),
                        messageMapper,
                        mock(GroupReadStateMapper.class),
                        mock(UserGroupPreferenceMapper.class),
                        access,
                        events,
                        groupAgents,
                        agents);
        chat =
                new MessageService(
                        messageMapper,
                        chatGroups,
                        mock(DirectConversationMapper.class),
                        mock(UserMapper.class),
                        chatGroupsService,
                        new ObjectMapper(),
                        events,
                        mock(NotificationService.class),
                        new MessageViews(
                                mock(UserMapper.class),
                                agentMapper,
                                mock(AttachmentService.class),
                                new ObjectMapper()),
                        eventPublisher);
        groupMemories =
                new GroupMemoryService(
                        access,
                        chatGroupsService,
                        directories,
                        document,
                        new GroupMemoryMetaStore());
        projectMemories = new ProjectMemoryService(access, projects, directories, events, document);
        service =
                new ProjectMemoryDiscoveryService(
                        chatGroups,
                        groupMemories,
                        projectMemories,
                        chat,
                        agents,
                        model,
                        Runnable::run,
                        properties(),
                        document);
        when(messageMapper.nextSequence(any())).thenReturn(1L);
        when(messageMapper.insert(any(MessageEntity.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(0, MessageEntity.class).setId(TestIds.next());
                    return 1;
                });
    }

    /** 服务用 MyBatis-Plus 构造 chat_groups 查询，需要先注册实体元数据。 */
    private static void initializeMybatisMetadata() {
        for (Class<?> entity : List.of(ChatGroupEntity.class, MessageEntity.class)) {
            if (TableInfoHelper.getTableInfo(entity) == null) {
                TableInfoHelper.initTableInfo(
                        new MapperBuilderAssistant(
                                new MybatisConfiguration(), "ProjectMemoryDiscoveryServiceTest"),
                        entity);
            }
        }
    }

    @Test
    void discoveredSuggestionIsPostedByTheDefaultAgentWithoutWritingProjectMemory() {
        Long project = TestIds.next(), group = TestIds.next(), agent = TestIds.next();
        groupMemories.writeByAgent(project, group, "- 议题：登录方式\n- 结论：先做邮箱登录");
        defaultGroupWithAgent(project, group, agent);
        when(model.complete(any(), any())).thenReturn("- 新增：登录方式先用邮箱登录");

        service.discover(project, group);

        // 群记忆是唯一的提炼来源：模型拿到的是群记忆正文和当前项目记忆，而不是原始聊天
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(model).complete(any(), prompt.capture());
        assertThat(prompt.getValue()).contains("先做邮箱登录");
        // 建议由默认 Agent 在默认群以普通文本发出：走真实发送路径，发送者是 Agent 而非用户
        MessageEntity sent = insertedMessage();
        assertThat(sent.getSenderType()).isEqualTo("AGENT");
        assertThat(sent.getSenderAgentId()).isEqualTo(agent);
        assertThat(sent.getAuthorUserId()).isNull();
        assertThat(sent.getMessageType()).isEqualTo("TEXT");
        assertThat(sent.getContent()).contains("项目管理员", "- 新增：登录方式先用邮箱登录");
        // 建议本身不生效：写入要等管理员在对话里确认
        assertThat(projectMemories.read(project).getContent()).isEmpty();
        // 读过的版本记在 memory-meta.json，同一版本不再重复发现
        GroupMemoryMeta meta = groupMemories.meta(project, group);
        assertThat(meta.discoveredVersion())
                .isEqualTo(document.version("- 议题：登录方式\n- 结论：先做邮箱登录"));
        assertThat(meta.lastDiscoveredAt()).isNotNull();
    }

    @Test
    void restrictedGroupContentNeverReachesTheModelOrDefaultGroup() {
        Long project = TestIds.next(), group = TestIds.next();
        groupMemories.writeByAgent(project, group, "- 受限结论：调整了报价");
        when(chatGroups.selectById(group)).thenReturn(group(group, project, false));

        service.discover(project, group);

        verifyNoInteractions(model);
        verify(messageMapper, never()).insert(any(MessageEntity.class));
        assertThat(projectMemories.read(project).getContent()).isEmpty();
    }

    @Test
    void suggestionIsNotSentWhenTheDefaultAgentIsNotInTheGroup() {
        Long project = TestIds.next(), group = TestIds.next(), agent = TestIds.next();
        groupMemories.writeByAgent(project, group, "- 结论：先做邮箱登录");
        when(chatGroups.selectById(group)).thenReturn(group(group, project, true));
        when(agentMapper.selectDefaultAgent(project)).thenReturn(agent(agent));
        when(agentMapper.selectBatchIds(any())).thenReturn(List.of(agent(agent)));
        when(agentMapper.selectById(agent)).thenReturn(agent(agent));
        when(chatGroups.selectOne(any())).thenReturn(group(group, project, true));
        when(groupAgents.selectAgentIds(group)).thenReturn(List.of());
        when(model.complete(any(), any())).thenReturn("- 新增：登录方式先用邮箱登录");

        service.discover(project, group);

        verify(messageMapper, never()).insert(any(MessageEntity.class));
        // 失败不推进版本，下一轮仍会重试
        assertThat(groupMemories.meta(project, group).discoveredVersion()).isNull();
    }

    @Test
    void sameGroupMemoryVersionIsNotDiscoveredTwice() {
        Long project = TestIds.next(), group = TestIds.next();
        groupMemories.writeByAgent(project, group, "- 结论：先做邮箱登录");
        String version = document.version("- 结论：先做邮箱登录");
        groupMemories.markDiscovered(project, group, version, Instant.now());
        when(chatGroups.selectById(group)).thenReturn(group(group, project, true));

        service.discover(project, group);

        verifyNoInteractions(model);
        verify(messageMapper, never()).insert(any(MessageEntity.class));
        assertThat(groupMemories.meta(project, group).discoveredVersion()).isEqualTo(version);
    }

    @Test
    void nothingToDiscoverRecordsTheVersionWithoutPosting() {
        Long project = TestIds.next(), group = TestIds.next();
        groupMemories.writeByAgent(project, group, "- 闲聊：明天吃火锅");
        when(chatGroups.selectById(group)).thenReturn(group(group, project, true));
        when(model.complete(any(), any())).thenReturn("NONE.");

        service.discover(project, group);

        verify(messageMapper, never()).insert(any(MessageEntity.class));
        assertThat(groupMemories.meta(project, group).discoveredVersion())
                .isEqualTo(document.version("- 闲聊：明天吃火锅"));
    }

    @Test
    void failureIsRecordedForRetryAndTheNextAttemptRecovers() {
        Long project = TestIds.next(), group = TestIds.next(), agent = TestIds.next();
        groupMemories.writeByAgent(project, group, "- 结论：先做邮箱登录");
        defaultGroupWithAgent(project, group, agent);
        when(model.complete(any(), any()))
                .thenThrow(new IllegalStateException("模型调用超时"))
                .thenReturn("- 新增：登录方式先用邮箱登录");

        service.discover(project, group);

        verify(messageMapper, never()).insert(any(MessageEntity.class));
        assertThat(projectMemories.read(project).getContent()).isEmpty();
        assertThat(groupMemories.meta(project, group).discoveredVersion()).isNull();

        // 重试成功：发出建议并记录已发现的群记忆版本
        service.discover(project, group);

        assertThat(insertedMessage().getSenderAgentId()).isEqualTo(agent);
        assertThat(groupMemories.meta(project, group).discoveredVersion())
                .isEqualTo(document.version("- 结论：先做邮箱登录"));
    }

    @Test
    void scanSubmitsDueGroupsToTheBackgroundExecutorInsteadOfCallingTheModelInline() {
        Long project = TestIds.next(), group = TestIds.next(), agent = TestIds.next();
        groupMemories.writeByAgent(project, group, "- 结论：先做邮箱登录");
        defaultGroupWithAgent(project, group, agent);
        when(model.complete(any(), any())).thenReturn("- 新增：登录方式先用邮箱登录");
        when(chatGroups.selectList(any())).thenReturn(List.of(group(group, project, true)));
        List<Runnable> queued = new ArrayList<>();
        ProjectMemoryDiscoveryService scheduled =
                new ProjectMemoryDiscoveryService(
                        chatGroups,
                        groupMemories,
                        projectMemories,
                        chat,
                        agents,
                        model,
                        queued::add,
                        properties(),
                        document);

        scheduled.scan();

        verifyNoInteractions(model);
        assertThat(queued).hasSize(1);
        queued.getFirst().run();
        verify(model).complete(any(), any());
        assertThat(insertedMessage().getSenderAgentId()).isEqualTo(agent);
    }

    @Test
    void scanSkipsGroupsWhoseMemoryVersionHasNotChanged() {
        Long project = TestIds.next(), group = TestIds.next();
        groupMemories.writeByAgent(project, group, "- 结论：先做邮箱登录");
        // 已到期，但群记忆内容版本与上次发现时一致：不必再读模型。
        groupMemories.markDiscovered(
                project,
                group,
                document.version("- 结论：先做邮箱登录"),
                Instant.now().minus(Duration.ofMinutes(INTERVAL_MINUTES + 1)));
        when(chatGroups.selectList(any())).thenReturn(List.of(group(group, project, true)));
        List<Runnable> queued = new ArrayList<>();

        new ProjectMemoryDiscoveryService(
                        chatGroups,
                        groupMemories,
                        projectMemories,
                        chat,
                        agents,
                        model,
                        queued::add,
                        properties(),
                        document)
                .scan();

        assertThat(queued).isEmpty();
        verifyNoInteractions(model);
    }

    @Test
    void scanSkipsGroupsWhoseDiscoveryIntervalHasNotElapsed() {
        Long project = TestIds.next(), group = TestIds.next();
        groupMemories.writeByAgent(project, group, "- 结论：先做邮箱登录");
        // 群记忆版本变过，但距离上次发现还不到一个周期：等下一轮再说。
        groupMemories.markDiscovered(project, group, "older-version", Instant.now());
        when(chatGroups.selectList(any())).thenReturn(List.of(group(group, project, true)));
        List<Runnable> queued = new ArrayList<>();

        new ProjectMemoryDiscoveryService(
                        chatGroups,
                        groupMemories,
                        projectMemories,
                        chat,
                        agents,
                        model,
                        queued::add,
                        properties(),
                        document)
                .scan();

        assertThat(queued).isEmpty();
    }

    @Test
    void scanResubmitsGroupsWhoseMemoryChangedAfterTheInterval() {
        Long project = TestIds.next(), group = TestIds.next();
        groupMemories.writeByAgent(project, group, "- 结论：先做邮箱登录");
        groupMemories.markDiscovered(
                project,
                group,
                "older-version",
                Instant.now().minus(Duration.ofMinutes(INTERVAL_MINUTES + 1)));
        when(chatGroups.selectList(any())).thenReturn(List.of(group(group, project, true)));
        List<Runnable> queued = new ArrayList<>();

        new ProjectMemoryDiscoveryService(
                        chatGroups,
                        groupMemories,
                        projectMemories,
                        chat,
                        agents,
                        model,
                        queued::add,
                        properties(),
                        document)
                .scan();

        assertThat(queued).hasSize(1);
    }

    @Test
    void scanQueriesOnlyProjectGroupsThatAreDefaultGroups() {
        when(chatGroups.selectList(any())).thenReturn(List.of());

        service.scan();

        ArgumentCaptor<Wrapper<ChatGroupEntity>> query = ArgumentCaptor.captor();
        verify(chatGroups).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("kind", "default_group");
    }

    @Test
    void fullQueueSkipsThisRoundWithoutFailingTheScan() {
        Long project = TestIds.next(), group = TestIds.next();
        groupMemories.writeByAgent(project, group, "- 结论：先做邮箱登录");
        when(chatGroups.selectList(any())).thenReturn(List.of(group(group, project, true)));
        ProjectMemoryDiscoveryService saturated =
                new ProjectMemoryDiscoveryService(
                        chatGroups,
                        groupMemories,
                        projectMemories,
                        chat,
                        agents,
                        model,
                        task -> {
                            throw new RejectedExecutionException("队列已满");
                        },
                        properties(),
                        document);

        saturated.scan();

        verifyNoInteractions(model);
    }

    private void defaultGroupWithAgent(Long project, Long group, Long agent) {
        when(chatGroups.selectById(group)).thenReturn(group(group, project, true));
        when(chatGroups.selectOne(any())).thenReturn(group(group, project, true));
        when(agentMapper.selectDefaultAgent(project)).thenReturn(agent(agent));
        when(agentMapper.selectBatchIds(any())).thenReturn(List.of(agent(agent)));
        when(groupAgents.selectAgentIds(group)).thenReturn(List.of(agent));
    }

    private MessageEntity insertedMessage() {
        ArgumentCaptor<MessageEntity> captor = ArgumentCaptor.captor();
        verify(messageMapper).insert(captor.capture());
        return captor.getValue();
    }

    private ProjectMemoryDiscoveryProperties properties() {
        ProjectMemoryDiscoveryProperties properties = new ProjectMemoryDiscoveryProperties();
        properties.setIntervalMinutes(INTERVAL_MINUTES);
        properties.setRetryDelayMinutes(RETRY_DELAY_MINUTES);
        properties.setMaxAttempts(MAX_ATTEMPTS);
        properties.setBatchSize(BATCH_SIZE);
        return properties;
    }

    private ChatGroupEntity group(Long groupId, Long projectId, boolean defaultGroup) {
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        group.setKind(ChatGroupEntity.KIND_GROUP);
        group.setDefaultGroup(defaultGroup);
        return group;
    }

    private AgentEntity agent(Long agentId) {
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setName("Qgent");
        agent.setDefaultAgent(true);
        return agent;
    }
}

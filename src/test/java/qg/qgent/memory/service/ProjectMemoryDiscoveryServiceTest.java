package qg.qgent.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.BeforeAll;
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
import qg.qgent.support.MapperTestSupport;
import qg.qgent.support.TestIds;
import qg.qgent.user.mapper.UserGroupPreferenceMapper;
import qg.qgent.user.mapper.UserMapper;

/**
 * 项目记忆发现：扫描项目内每个非私聊群、按 memory-meta.json 跳过未变更版本，
 * 建议统一由默认 Agent 发到项目默认群并写明来源群。
 */
class ProjectMemoryDiscoveryServiceTest {

    private static final int MAX_CHARS = 2000;
    private static final int INTERVAL_MINUTES = 1440;
    private static final int RETRY_DELAY_MINUTES = 60;
    private static final int MAX_ATTEMPTS = 3;
    private static final int BATCH_SIZE = 10;

    @TempDir Path root;

    private static MybatisConfiguration mapperConfiguration;

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

    /** 真实 Mapper 注册流程解析 ChatGroupEntity 列名，lambda 条件不需要手工初始化 TableInfo。 */
    @BeforeAll
    static void registerMapperMetadata() {
        mapperConfiguration = MapperTestSupport.configuration(ChatGroupMapper.class);
        new MybatisSqlSessionFactoryBuilder().build(mapperConfiguration);
    }

    @BeforeEach
    void setUp() {
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
        service = scheduled(Runnable::run);
        when(messageMapper.nextSequence(any())).thenReturn(1L);
        when(messageMapper.insert(any(MessageEntity.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(0, MessageEntity.class).setId(TestIds.next());
                    return 1;
                });
    }

    @Test
    void discoveredSuggestionIsPostedByTheDefaultAgentWithoutWritingProjectMemory() {
        Long project = TestIds.next(), group = TestIds.next(), agent = TestIds.next();
        groupMemories.writeByAgent(project, group, "- 议题：登录方式\n- 结论：先做邮箱登录");
        defaultGroupWithAgent(project, group, agent, "项目群聊");
        when(model.complete(any(), any())).thenReturn("- 新增：登录方式先用邮箱登录");

        service.discover(project, group);

        // 群记忆是唯一的提炼来源：模型拿到的是群记忆正文和当前项目记忆，而不是原始聊天
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(model).complete(any(), prompt.capture());
        assertThat(prompt.getValue()).contains("先做邮箱登录", "项目群聊");
        // 建议由默认 Agent 在默认群以普通文本发出：走真实发送路径，发送者是 Agent 而非用户
        MessageEntity sent = insertedMessage();
        assertThat(sent.getSenderType()).isEqualTo("AGENT");
        assertThat(sent.getSenderAgentId()).isEqualTo(agent);
        assertThat(sent.getAuthorUserId()).isNull();
        assertThat(sent.getMessageType()).isEqualTo("TEXT");
        assertThat(sent.getContent()).contains("项目管理员", "- 新增：登录方式先用邮箱登录", "项目群聊");
        // 建议本身不生效：写入要等管理员在对话里确认
        assertThat(projectMemories.read(project).getContent()).isEmpty();
        // 读过的版本记在 memory-meta.json，同一版本不再重复发现
        GroupMemoryMeta meta = groupMemories.meta(project, group);
        assertThat(meta.discoveredVersion())
                .isEqualTo(document.version("- 议题：登录方式\n- 结论：先做邮箱登录"));
        assertThat(meta.lastDiscoveredAt()).isNotNull();
    }

    @Test
    void suggestionFromANonDefaultGroupGoesToTheDefaultGroupWithTheSourceName() {
        Long project = TestIds.next(), source = TestIds.next(), target = TestIds.next();
        Long agent = TestIds.next();
        ChatGroupEntity sourceGroup = group(source, project, ChatGroupEntity.KIND_GROUP, false, "研发讨论");
        groupMemories.writeByAgent(project, source, "- 结论：先做邮箱登录");
        when(chatGroups.selectById(source)).thenReturn(sourceGroup);
        when(chatGroups.selectOne(any()))
                .thenReturn(group(target, project, ChatGroupEntity.KIND_GROUP, true, "项目群聊"));
        when(agentMapper.selectDefaultAgent(project)).thenReturn(agent(agent));
        when(agentMapper.selectBatchIds(any())).thenReturn(List.of(agent(agent)));
        when(groupAgents.selectAgentIds(target)).thenReturn(List.of(agent));
        when(model.complete(any(), any())).thenReturn("- 新增：登录方式先用邮箱登录");

        service.discover(project, source);

        // 消息只进默认群，来源群名称出现在模型提示和消息正文里
        MessageEntity sent = insertedMessage();
        assertThat(sent.getGroupId()).isEqualTo(target);
        assertThat(sent.getSenderAgentId()).isEqualTo(agent);
        assertThat(sent.getContent()).contains("研发讨论", "- 新增：登录方式先用邮箱登录");
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(model).complete(systemPrompt.capture(), prompt.capture());
        assertThat(systemPrompt.getValue()).contains("研发讨论");
        assertThat(prompt.getValue()).contains("研发讨论", "先做邮箱登录");
        // 来源群自己的发现版本照常推进
        assertThat(groupMemories.meta(project, source).discoveredVersion())
                .isEqualTo(document.version("- 结论：先做邮箱登录"));
        assertThat(projectMemories.read(project).getContent()).isEmpty();
    }

    @Test
    void directConversationContentNeverReachesTheModelOrDefaultGroup() {
        Long project = TestIds.next(), direct = TestIds.next();
        groupMemories.writeByAgent(project, direct, "- 私下结论：调整了报价");
        when(chatGroups.selectById(direct))
                .thenReturn(group(direct, project, ChatGroupEntity.KIND_DIRECT, false, "私聊"));

        service.discover(project, direct);

        verifyNoInteractions(model);
        verify(messageMapper, never()).insert(any(MessageEntity.class));
        assertThat(projectMemories.read(project).getContent()).isEmpty();
    }

    @Test
    void suggestionIsNotSentWhenTheDefaultAgentIsNotInTheGroup() {
        Long project = TestIds.next(), group = TestIds.next(), agent = TestIds.next();
        groupMemories.writeByAgent(project, group, "- 结论：先做邮箱登录");
        when(chatGroups.selectById(group))
                .thenReturn(group(group, project, ChatGroupEntity.KIND_GROUP, true, "项目群聊"));
        when(chatGroups.selectOne(any()))
                .thenReturn(group(group, project, ChatGroupEntity.KIND_GROUP, true, "项目群聊"));
        when(agentMapper.selectDefaultAgent(project)).thenReturn(agent(agent));
        when(agentMapper.selectBatchIds(any())).thenReturn(List.of(agent(agent)));
        when(agentMapper.selectById(agent)).thenReturn(agent(agent));
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
        when(chatGroups.selectById(group))
                .thenReturn(group(group, project, ChatGroupEntity.KIND_GROUP, false, "研发讨论"));

        service.discover(project, group);

        verifyNoInteractions(model);
        verify(messageMapper, never()).insert(any(MessageEntity.class));
        assertThat(groupMemories.meta(project, group).discoveredVersion()).isEqualTo(version);
    }

    @Test
    void nothingToDiscoverRecordsTheVersionWithoutPosting() {
        Long project = TestIds.next(), group = TestIds.next();
        groupMemories.writeByAgent(project, group, "- 闲聊：明天吃火锅");
        when(chatGroups.selectById(group))
                .thenReturn(group(group, project, ChatGroupEntity.KIND_GROUP, false, "研发讨论"));
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
        defaultGroupWithAgent(project, group, agent, "项目群聊");
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
        Long project = TestIds.next(), source = TestIds.next(), target = TestIds.next();
        Long agent = TestIds.next();
        groupMemories.writeByAgent(project, source, "- 结论：先做邮箱登录");
        ChatGroupEntity sourceGroup =
                group(source, project, ChatGroupEntity.KIND_GROUP, false, "研发讨论");
        when(chatGroups.selectById(source)).thenReturn(sourceGroup);
        when(chatGroups.selectOne(any()))
                .thenReturn(group(target, project, ChatGroupEntity.KIND_GROUP, true, "项目群聊"));
        when(agentMapper.selectDefaultAgent(project)).thenReturn(agent(agent));
        when(agentMapper.selectBatchIds(any())).thenReturn(List.of(agent(agent)));
        when(groupAgents.selectAgentIds(target)).thenReturn(List.of(agent));
        when(model.complete(any(), any())).thenReturn("- 新增：登录方式先用邮箱登录");
        when(chatGroups.selectList(any()))
                .thenAnswer(
                        invocation -> scannedPage(List.of(sourceGroup), invocation.getArgument(0)));
        List<Runnable> queued = new ArrayList<>();

        scheduled(queued::add).scan();

        verifyNoInteractions(model);
        assertThat(queued).hasSize(1);
        queued.getFirst().run();
        verify(model).complete(any(), any());
        MessageEntity sent = insertedMessage();
        assertThat(sent.getGroupId()).isEqualTo(target);
        assertThat(sent.getSenderAgentId()).isEqualTo(agent);
        assertThat(sent.getContent()).contains("研发讨论");
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
        when(chatGroups.selectList(any()))
                .thenAnswer(
                        invocation ->
                                scannedPage(
                                        List.of(
                                                group(
                                                        group,
                                                        project,
                                                        ChatGroupEntity.KIND_GROUP,
                                                        false,
                                                        "研发讨论")),
                                        invocation.getArgument(0)));
        List<Runnable> queued = new ArrayList<>();

        scheduled(queued::add).scan();

        assertThat(queued).isEmpty();
        verifyNoInteractions(model);
    }

    @Test
    void scanSkipsGroupsWhoseDiscoveryIntervalHasNotElapsed() {
        Long project = TestIds.next(), group = TestIds.next();
        groupMemories.writeByAgent(project, group, "- 结论：先做邮箱登录");
        // 群记忆版本变过，但距离上次发现还不到一个周期：等下一轮再说。
        groupMemories.markDiscovered(project, group, "older-version", Instant.now());
        when(chatGroups.selectList(any()))
                .thenAnswer(
                        invocation ->
                                scannedPage(
                                        List.of(
                                                group(
                                                        group,
                                                        project,
                                                        ChatGroupEntity.KIND_GROUP,
                                                        false,
                                                        "研发讨论")),
                                        invocation.getArgument(0)));
        List<Runnable> queued = new ArrayList<>();

        scheduled(queued::add).scan();

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
        when(chatGroups.selectList(any()))
                .thenAnswer(
                        invocation ->
                                scannedPage(
                                        List.of(
                                                group(
                                                        group,
                                                        project,
                                                        ChatGroupEntity.KIND_GROUP,
                                                        false,
                                                        "研发讨论")),
                                        invocation.getArgument(0)));
        List<Runnable> queued = new ArrayList<>();

        scheduled(queued::add).scan();

        assertThat(queued).hasSize(1);
    }

    @Test
    void scanFindsDueGroupsBeyondTheFirstPageOfUnchangedGroups() {
        Long project = TestIds.next();
        // 一整页版本未变的群排在前面；只读一页的实现永远看不到后面的群。
        List<ChatGroupEntity> unchanged = new ArrayList<>();
        for (int index = 0; index < BATCH_SIZE; index++) {
            Long groupId = TestIds.next();
            String content = "- 结论：" + groupId;
            groupMemories.writeByAgent(project, groupId, content);
            groupMemories.markDiscovered(
                    project,
                    groupId,
                    document.version(content),
                    Instant.now().minus(Duration.ofMinutes(INTERVAL_MINUTES + 1)));
            unchanged.add(
                    group(
                            groupId,
                            project,
                            ChatGroupEntity.KIND_GROUP,
                            false,
                            "群" + groupId));
        }
        Long changedId = TestIds.next();
        groupMemories.writeByAgent(project, changedId, "- 结论：先做邮箱登录");
        groupMemories.markDiscovered(
                project,
                changedId,
                "older-version",
                Instant.now().minus(Duration.ofMinutes(INTERVAL_MINUTES + 1)));
        when(chatGroups.selectList(any()))
                .thenAnswer(invocation -> scannedPage(unchanged, invocation.getArgument(0)))
                .thenAnswer(
                        invocation ->
                                scannedPage(
                                        List.of(
                                                group(
                                                        changedId,
                                                        project,
                                                        ChatGroupEntity.KIND_GROUP,
                                                        false,
                                                        "群" + changedId)),
                                        invocation.getArgument(0)));
        List<Runnable> queued = new ArrayList<>();

        scheduled(queued::add).scan();

        // 第一页没有到期群，仍要按游标继续翻页，才能发现后面的群。
        assertThat(queued).hasSize(1);
    }

    @Test
    void fullQueueSkipsThisRoundWithoutFailingTheScan() {
        Long project = TestIds.next(), group = TestIds.next();
        groupMemories.writeByAgent(project, group, "- 结论：先做邮箱登录");
        when(chatGroups.selectList(any()))
                .thenAnswer(
                        invocation ->
                                scannedPage(
                                        List.of(
                                                group(
                                                        group,
                                                        project,
                                                        ChatGroupEntity.KIND_GROUP,
                                                        false,
                                                        "研发讨论")),
                                        invocation.getArgument(0)));
        ProjectMemoryDiscoveryService saturated =
                scheduled(
                        task -> {
                            throw new RejectedExecutionException("队列已满");
                        });

        saturated.scan();

        verifyNoInteractions(model);
    }

    /**
     * 扫描分页用真实生成的 MappedStatement 执行一次 BoundSql 解析：查询只限定群聊行，
     * 不再限定默认群，避免只测到桩的返回值而漏掉查询条件。
     */
    private static List<ChatGroupEntity> scannedPage(
            List<ChatGroupEntity> page, Wrapper<ChatGroupEntity> wrapper) {
        BoundSql sql =
                mapperConfiguration
                        .getMappedStatement(ChatGroupMapper.class.getName() + ".selectList")
                        .getBoundSql(Map.of("ew", wrapper));
        assertThat(sql.getSql()).contains("chat_groups");
        // 只看 WHERE 条件：SELECT 列里本来就有 default_group 字段。
        String where = sql.getSql().substring(sql.getSql().indexOf("WHERE"));
        assertThat(where).contains("kind = ?").doesNotContain("default_group");
        assertThat(((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs().values())
                .contains(ChatGroupEntity.KIND_GROUP);
        return page;
    }

    private ProjectMemoryDiscoveryService scheduled(Executor executor) {
        return new ProjectMemoryDiscoveryService(
                chatGroups,
                groupMemories,
                projectMemories,
                chat,
                agents,
                model,
                executor,
                properties(),
                document);
    }

    private void defaultGroupWithAgent(Long project, Long group, Long agent, String groupName) {
        ChatGroupEntity defaultGroup =
                group(group, project, ChatGroupEntity.KIND_GROUP, true, groupName);
        when(chatGroups.selectById(group)).thenReturn(defaultGroup);
        when(chatGroups.selectOne(any())).thenReturn(defaultGroup);
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

    private ChatGroupEntity group(
            Long groupId, Long projectId, String kind, boolean defaultGroup, String name) {
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        group.setKind(kind);
        group.setDefaultGroup(defaultGroup);
        group.setName(name);
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

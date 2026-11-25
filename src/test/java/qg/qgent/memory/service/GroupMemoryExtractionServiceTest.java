package qg.qgent.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.service.GroupService;
import qg.qgent.chat.service.MessageService;
import qg.qgent.harness.model.ModelClient;
import qg.qgent.memory.config.GroupMemoryProperties;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.support.TestIds;

/** 群记忆提炼：三档触发、水位推进、乐观锁冲突、失败退避与翻页扫描。 */
class GroupMemoryExtractionServiceTest {

    private static final int MIN_MESSAGES = 3;
    private static final int IDLE_MINUTES = 15;
    private static final int STALE_MIN_MESSAGES = 2;
    private static final int STALE_IDLE_MINUTES = 240;
    private static final int FORCE_MESSAGES = 8;
    private static final int MAX_ATTEMPTS = 2;
    private static final int RETRY_DELAY_MINUTES = 5;
    private static final int MAX_MESSAGES_PER_RUN = 200;
    private static final int SCAN_PAGE_SIZE = 20;
    private static final Long AGENT_ID = 4242L;

    @TempDir Path root;

    private final ChatGroupMapper groups = mock(ChatGroupMapper.class);
    private final MessageMapper chatMessages = mock(MessageMapper.class);
    private final MessageService chat = mock(MessageService.class);
    private final ModelClient model = mock(ModelClient.class);
    private final Executor executor = Runnable::run;
    private final MemoryDocument document = new MemoryDocument(12000);
    private GroupMemoryService memories;
    private GroupMemoryExtractionService service;

    @BeforeEach
    void setUp() {
        initializeMybatisMetadata();
        memories =
                new GroupMemoryService(
                        mock(ProjectAccessService.class),
                        mock(GroupService.class),
                        new ProjectDirectoryService(root.toString(), root.toString()),
                        document,
                        new GroupMemoryMetaStore());
        when(chat.agentInstruction(any()))
                .thenAnswer(invocation -> ((MessageEntity) invocation.getArgument(0)).getContent());
        service =
                new GroupMemoryExtractionService(
                        groups,
                        chatMessages,
                        chat,
                        memories,
                        model,
                        executor,
                        properties(),
                        document);
    }

    /** 服务用 MyBatis-Plus 构造 chat_groups 查询，需要先注册实体元数据。 */
    private static void initializeMybatisMetadata() {
        if (TableInfoHelper.getTableInfo(ChatGroupEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(
                            new MybatisConfiguration(), "GroupMemoryExtractionServiceTest"),
                    ChatGroupEntity.class);
        }
    }

    @Test
    void extractsWhenRegularThresholdAndIdleTimeAreMet() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        List<MessageEntity> messages =
                List.of(
                        message(groupId, 1, 7L, "先做邮箱登录"),
                        message(groupId, 2, 7L, "同意"),
                        message(groupId, 3, null, "结论：邮箱登录"));
        givenGroup(projectId, groupId, minutesAgo(20), messages);
        when(model.complete(any(), any())).thenReturn("# 群记忆\n- [2026-08-18] 结论：邮箱登录");

        service.scan();

        assertThat(memories.read(projectId, groupId).getContent())
                .isEqualTo("# 群记忆\n- [2026-08-18] 结论：邮箱登录");
        GroupMemoryMeta meta = memories.meta(projectId, groupId);
        assertThat(meta.lastProcessedSequence()).isEqualTo(3);
        assertThat(meta.revision()).isEqualTo(1);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(model).complete(any(), prompt.capture());
        // 群里有多个 Agent：发言按发送者 ID 标注，不能一律当成默认 Agent。
        assertThat(prompt.getValue())
                .contains("先做邮箱登录", "用户 7：同意", "Agent " + AGENT_ID + "：结论：邮箱登录")
                .doesNotContain("Qgent");
    }

    @Test
    void waitsForIdleTimeBeforeExtracting() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        givenGroup(
                projectId,
                groupId,
                minutesAgo(1),
                List.of(
                        message(groupId, 1, 7L, "a"),
                        message(groupId, 2, 7L, "b"),
                        message(groupId, 3, 7L, "c")));

        service.scan();

        verifyNoInteractions(model);
        assertThat(memories.meta(projectId, groupId).lastProcessedSequence()).isZero();
    }

    @Test
    void waitsForMoreMessagesWhileTheDiscussionIsStillRecent() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        // 达到长闲置下限的消息数，但静默时间远不到 4 小时：等常规门槛
        givenGroup(
                projectId,
                groupId,
                minutesAgo(30),
                List.of(message(groupId, 1, 7L, "a"), message(groupId, 2, 7L, "b")));

        service.scan();

        verifyNoInteractions(model);
    }

    @Test
    void longIdleFallbackExtractsFewNewMessagesAfterHoursOfSilence() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        givenGroup(
                projectId,
                groupId,
                minutesAgo(STALE_IDLE_MINUTES + 60),
                List.of(message(groupId, 1, 7L, "a"), message(groupId, 2, 7L, "b")));
        when(model.complete(any(), any())).thenReturn("# 长闲置提炼");

        service.scan();

        verify(model).complete(any(), any());
        assertThat(memories.read(projectId, groupId).getContent()).isEqualTo("# 长闲置提炼");
        assertThat(memories.meta(projectId, groupId).lastProcessedSequence()).isEqualTo(2);
    }

    @Test
    void activeUpperBoundExtractsWithoutWaitingForIdleTime() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        List<MessageEntity> messages = new ArrayList<>();
        for (long sequence = 1; sequence <= FORCE_MESSAGES; sequence++) {
            messages.add(message(groupId, sequence, 7L, "消息 " + sequence));
        }
        // 群里刚刚还在说话，按常规规则还没到静默时间；活跃上限直接触发。
        givenGroup(projectId, groupId, minutesAgo(0), messages);
        when(model.complete(any(), any())).thenReturn("# 活跃上限提炼");

        service.scan();

        verify(model).complete(any(), any());
        assertThat(memories.meta(projectId, groupId).lastProcessedSequence())
                .isEqualTo(FORCE_MESSAGES);
    }

    @Test
    void groupsWithoutNewMessagesAreSkipped() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        memories.writeByAgent(projectId, groupId, "# 已提炼");
        memories.writeIfRevisionMatches(projectId, groupId, 1, "# 已提炼", 5);
        givenGroup(
                projectId,
                groupId,
                minutesAgo(60),
                List.of(message(groupId, 5, 7L, "已经提炼过")));

        service.scan();

        verifyNoInteractions(model);
    }

    @Test
    void discardsResultWhenAgentEditedMemoryDuringExtraction() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        memories.writeByAgent(projectId, groupId, "## 群内决策与规范\n- 旧结论");
        givenGroup(
                projectId,
                groupId,
                minutesAgo(20),
                List.of(
                        message(groupId, 1, 7L, "a"),
                        message(groupId, 2, 7L, "b"),
                        message(groupId, 3, 7L, "c")));
        // 提炼调用模型期间，Agent 直接改了群记忆：旧提炼结果不能覆盖它。
        when(model.complete(any(), any()))
                .thenAnswer(
                        invocation -> {
                            memories.edit(
                                    projectId,
                                    groupId,
                                    "## 群内决策与规范\n- 旧结论",
                                    "## 群内决策与规范\n- [2026-08-18] 新结论");
                            return "# 模型整理结果";
                        });

        service.scan();

        assertThat(memories.read(projectId, groupId).getContent())
                .isEqualTo("## 群内决策与规范\n- [2026-08-18] 新结论");
        assertThat(memories.meta(projectId, groupId).lastProcessedSequence()).isZero();
    }

    @Test
    void nextRunOnlySendsMessagesAboveTheSavedWatermark() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        memories.writeIfRevisionMatches(projectId, groupId, 0, "# 已提炼到第 3 条", 3);
        List<MessageEntity> newMessages =
                List.of(
                        message(groupId, 4, 7L, "a"),
                        message(groupId, 5, 7L, "b"),
                        message(groupId, 6, 7L, "c"));
        givenGroup(projectId, groupId, minutesAgo(20), newMessages);
        when(chatMessages.selectLatestSequence(groupId)).thenReturn(6L);
        when(model.complete(any(), any())).thenReturn("# 已提炼到第 6 条");

        service.scan();

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(model).complete(any(), prompt.capture());
        assertThat(prompt.getValue()).contains("已提炼到第 3 条", "用户 7：a", "用户 7：c");
        verify(chatMessages)
                .selectAfterSequence(eq(groupId), eq(3L), eq(MAX_MESSAGES_PER_RUN));
        assertThat(memories.meta(projectId, groupId).lastProcessedSequence()).isEqualTo(6);
    }

    @Test
    void failureKeepsPreviousMemoryAndWatermark() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        memories.writeByAgent(projectId, groupId, "# 旧群记忆");
        givenGroup(
                projectId,
                groupId,
                minutesAgo(20),
                List.of(
                        message(groupId, 1, 7L, "a"),
                        message(groupId, 2, 7L, "b"),
                        message(groupId, 3, 7L, "c")));
        when(model.complete(any(), any())).thenThrow(new IllegalStateException("模型调用超时"));

        service.scan();

        assertThat(memories.read(projectId, groupId).getContent()).isEqualTo("# 旧群记忆");
        assertThat(memories.meta(projectId, groupId).lastProcessedSequence()).isZero();
    }

    @Test
    void waitsForRetryDelayAfterFailure() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        givenGroup(
                projectId,
                groupId,
                minutesAgo(20),
                List.of(
                        message(groupId, 1, 7L, "a"),
                        message(groupId, 2, 7L, "b"),
                        message(groupId, 3, 7L, "c")));
        when(model.complete(any(), any())).thenThrow(new IllegalStateException("模型调用超时"));

        service.scan();
        service.scan();

        // 失败后 5 分钟内不重试，避免连续失败刷爆模型调用。
        verify(model, times(1)).complete(any(), any());
    }

    @Test
    void givesUpAfterMaxAttemptsUntilNewMessagesArrive() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        // 重试间隔设为 0，只验证连续失败上限；新消息到来后重新允许提炼。
        GroupMemoryProperties noDelay = properties();
        noDelay.setRetryDelayMinutes(0);
        GroupMemoryExtractionService eager =
                new GroupMemoryExtractionService(
                        groups, chatMessages, chat, memories, model, executor, noDelay, document);
        givenGroup(
                projectId,
                groupId,
                minutesAgo(20),
                List.of(
                        message(groupId, 1, 7L, "a"),
                        message(groupId, 2, 7L, "b"),
                        message(groupId, 3, 7L, "c")));
        when(model.complete(any(), any())).thenThrow(new IllegalStateException("模型调用超时"));

        eager.scan();
        eager.scan();
        eager.scan();

        verify(model, times(MAX_ATTEMPTS)).complete(any(), any());

        when(chatMessages.selectLatestSequence(groupId)).thenReturn(4L);
        when(chatMessages.selectAfterSequence(eq(groupId), anyLong(), anyInt()))
                .thenReturn(
                        List.of(
                                message(groupId, 1, 7L, "a"),
                                message(groupId, 2, 7L, "b"),
                                message(groupId, 3, 7L, "c"),
                                message(groupId, 4, 7L, "d")));
        // 之前的行为是抛异常，只能用 doReturn 覆盖，避免在打桩时再次抛错。
        doReturn("# 恢复后的群记忆").when(model).complete(any(), any());

        eager.scan();

        assertThat(memories.read(projectId, groupId).getContent()).isEqualTo("# 恢复后的群记忆");
    }

    @Test
    void rendersSystemMessagesWithoutUserPlaceholder() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        MessageEntity system = message(groupId, 1, null, "群已创建");
        system.setSenderType("SYSTEM");
        givenGroup(
                projectId,
                groupId,
                minutesAgo(20),
                List.of(system, message(groupId, 2, 7L, "b"), message(groupId, 3, 7L, "c")));
        when(model.complete(any(), any())).thenReturn("# 群记忆");

        service.scan();

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(model).complete(any(), prompt.capture());
        assertThat(prompt.getValue()).contains("系统：群已创建").doesNotContain("用户 null");
    }

    @Test
    void promptRequiresFixedSectionsAndCompression() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        givenGroup(
                projectId,
                groupId,
                minutesAgo(20),
                List.of(
                        message(groupId, 1, 7L, "a"),
                        message(groupId, 2, 7L, "b"),
                        message(groupId, 3, 7L, "c")));
        when(model.complete(any(), any())).thenReturn("# 群记忆");

        service.scan();

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(model).complete(system.capture(), any());
        assertThat(system.getValue())
                .contains("## 群内决策与规范", "## 成员要求与偏好", "## 当前进行中", "[YYYY-MM-DD]")
                .contains("整理与压缩", "删除");
    }

    @Test
    void stripsModelCodeFenceBeforeWritingMemory() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        givenGroup(
                projectId,
                groupId,
                minutesAgo(20),
                List.of(
                        message(groupId, 1, 7L, "a"),
                        message(groupId, 2, 7L, "b"),
                        message(groupId, 3, 7L, "c")));
        when(model.complete(any(), any())).thenReturn("```markdown\n# 群记忆\n```");

        service.scan();

        assertThat(memories.read(projectId, groupId).getContent()).isEqualTo("# 群记忆");
    }

    @Test
    void blankModelReplyFailsWithoutAdvancingProgress() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        givenGroup(
                projectId,
                groupId,
                minutesAgo(20),
                List.of(
                        message(groupId, 1, 7L, "a"),
                        message(groupId, 2, 7L, "b"),
                        message(groupId, 3, 7L, "c")));
        when(model.complete(any(), any())).thenReturn("   ");

        service.scan();

        assertThat(memories.meta(projectId, groupId).lastProcessedSequence()).isZero();
        assertThat(memories.read(projectId, groupId).getContent()).isEmpty();
    }

    @Test
    void scanContinuesWhenExtractionQueueIsFull() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        Long otherGroup = TestIds.next();
        givenGroup(projectId, groupId, minutesAgo(20), threeMessages(groupId));
        givenGroup(projectId, otherGroup, minutesAgo(19), threeMessages(otherGroup));
        when(groups.selectList(any()))
                .thenReturn(
                        List.of(
                                group(projectId, groupId, minutesAgo(20)),
                                group(projectId, otherGroup, minutesAgo(19))));
        Executor rejecting =
                task -> {
                    throw new RejectedExecutionException("队列已满");
                };
        GroupMemoryExtractionService fullQueue =
                new GroupMemoryExtractionService(
                        groups, chatMessages, chat, memories, model, rejecting, properties(), document);

        assertThatCode(fullQueue::scan).doesNotThrowAnyException();
        // 队列拒绝时没有开始提炼，组状态保持可重试。
        verifyNoInteractions(model);
    }

    @Test
    void findsWorkBeyondTheFirstPageOfAlreadyProcessedGroups() {
        Long projectId = TestIds.next();
        // 一整页已提炼完的安静群排在前面；只读一页的实现永远看不到后面的群
        List<ChatGroupEntity> quiet = new ArrayList<>();
        for (long groupId = 1; groupId <= SCAN_PAGE_SIZE; groupId++) {
            ChatGroupEntity group = group(projectId, groupId, minutesAgo(30));
            quiet.add(group);
            when(chatMessages.selectLatestSequence(groupId)).thenReturn(2L);
            memories.writeIfRevisionMatches(projectId, groupId, 0, "# 已提炼", 2);
        }
        Long activeGroupId = 100L;
        ChatGroupEntity active = group(projectId, activeGroupId, minutesAgo(20));
        when(chatMessages.selectLatestSequence(activeGroupId)).thenReturn(3L);
        when(chatMessages.selectAfterSequence(eq(activeGroupId), eq(0L), anyInt()))
                .thenReturn(threeMessages(activeGroupId));
        when(groups.selectList(any())).thenReturn(quiet).thenReturn(List.of(active));
        when(model.complete(any(), any())).thenReturn("# 群记忆");

        service.scan();

        // 第一页没有活可干的群，仍要按游标继续翻页，才能提炼后面的群
        verify(groups, times(2)).selectList(any());
        verify(model).complete(any(), any());
        assertThat(memories.meta(projectId, activeGroupId).lastProcessedSequence()).isEqualTo(3);
    }

    private void givenGroup(
            Long projectId,
            Long groupId,
            LocalDateTime lastMessageAt,
            List<MessageEntity> messages) {
        long latest = messages.stream()
                .mapToLong(MessageEntity::getSequenceNo)
                .max()
                .orElse(0);
        when(groups.selectList(any()))
                .thenReturn(List.of(group(projectId, groupId, lastMessageAt)));
        when(chatMessages.selectLatestSequence(groupId)).thenReturn(latest);
        when(chatMessages.selectAfterSequence(eq(groupId), anyLong(), anyInt()))
                .thenReturn(messages);
    }

    private GroupMemoryProperties properties() {
        GroupMemoryProperties properties = new GroupMemoryProperties();
        properties.setMinMessages(MIN_MESSAGES);
        properties.setIdleMinutes(IDLE_MINUTES);
        properties.setStaleMinMessages(STALE_MIN_MESSAGES);
        properties.setStaleIdleMinutes(STALE_IDLE_MINUTES);
        properties.setForceMessages(FORCE_MESSAGES);
        properties.setMaxAttempts(MAX_ATTEMPTS);
        properties.setRetryDelayMinutes(RETRY_DELAY_MINUTES);
        properties.setMaxMessagesPerRun(MAX_MESSAGES_PER_RUN);
        properties.setScanPageSize(SCAN_PAGE_SIZE);
        return properties;
    }

    private ChatGroupEntity group(Long projectId, Long groupId, LocalDateTime lastMessageAt) {
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        group.setKind(ChatGroupEntity.KIND_GROUP);
        group.setLastMessageAt(lastMessageAt);
        return group;
    }

    private List<MessageEntity> threeMessages(Long groupId) {
        return List.of(
                message(groupId, 1, 7L, "a"),
                message(groupId, 2, 7L, "b"),
                message(groupId, 3, 7L, "c"));
    }

    private LocalDateTime minutesAgo(int minutes) {
        return LocalDateTime.now(ZoneOffset.UTC).minusMinutes(minutes);
    }

    private MessageEntity message(Long groupId, long sequence, Long authorUserId, String text) {
        MessageEntity message = new MessageEntity();
        message.setGroupId(groupId);
        message.setSequenceNo(sequence);
        message.setAuthorUserId(authorUserId);
        message.setSenderType(authorUserId == null ? "AGENT" : "USER");
        message.setSenderAgentId(authorUserId == null ? AGENT_ID : null);
        message.setMessageType("TEXT");
        message.setContent(text);
        return message;
    }
}

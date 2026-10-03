package qg.qgent.realtime.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.common.config.MdcTaskDecorator;
import qg.qgent.common.config.PerformanceMetrics;
import qg.qgent.common.util.UuidV7;
import qg.qgent.common.web.ApiException;
import qg.qgent.notification.entity.NotificationEventEntity;
import qg.qgent.notification.mapper.NotificationEventMapper;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.entity.EventEntity;
import qg.qgent.realtime.mapper.EventMapper;
import qg.qgent.realtime.websocket.RealtimeFrame;
import qg.qgent.team.entity.TeamEventEntity;
import qg.qgent.team.mapper.TeamEventMapper;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.team.mapper.TeamMemberMapper;
import qg.qgent.user.mapper.UserMapper;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** 保存项目、团队和通知事件，通过 WebSocket 和 SSE 推送给对应用户。 */
@Service
public class EventService {
    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    // SSE 连接保活心跳间隔（秒），每 15 秒一次。
    private static final long HEARTBEAT_SECONDS = 15;
    // 增量事件轮询间隔（毫秒）。
    private static final long POLL_INTERVAL_MS = 2000;
    // 单次轮询批量拉取上限。
    private static final int BATCH_SIZE = 100;
    // 事件保留时长（小时），过期事件不再可续传。
    private static final int RETENTION_HOURS = 24;
    // SSE 连接超时（毫秒）；超时后释放连接，由客户端按 Last-Event-ID 重连。
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;
    // 三类 SSE 共用的连接上限；超过时返回 429。
    private static final int MAX_SSE_CONNECTIONS = 128;
    // 同一项目事件写入发生死锁时的最大新事务重试次数。
    private static final int EVENT_DEADLOCK_RETRIES = 3;
    // 死锁重试的初始退避时间（毫秒）。
    private static final long EVENT_DEADLOCK_BACKOFF_MS = 50L;

    private final EventMapper eventMapper;
    private final ProjectAccessService projectAccess;
    private final NotificationEventMapper notificationEventMapper;
    private final TeamEventMapper teamEventMapper;
    private final ExecutorService executor;
    private final RealtimeHub realtimeHub;
    private final ProjectMemberMapper projectMemberMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final ChatGroupMapper groupMapper;
    private final ProjectMapper projectMapper;
    private final UserMapper userMapper;
    private final TeamMapper teamMapper;
    private final TransactionTemplate eventTransaction;
    private final PerformanceMetrics metrics;
    // 每个 EventService Bean 专用的事务同步资源键，保存待提交的项目事件队列。
    private final Object projectEventQueueResource = new Object();
    private final AtomicInteger activeSseConnections = new AtomicInteger();

    @Autowired
    public EventService(EventMapper eventMapper, ProjectAccessService projectAccess,
                        NotificationEventMapper notificationEventMapper, TeamEventMapper teamEventMapper,
                        RealtimeHub realtimeHub,
                        ProjectMemberMapper projectMemberMapper, TeamMemberMapper teamMemberMapper,
                        ChatGroupMapper groupMapper,
                        ProjectMapper projectMapper, UserMapper userMapper, TeamMapper teamMapper,
                        PlatformTransactionManager transactionManager, PerformanceMetrics metrics) {
        this.eventMapper = eventMapper;
        this.projectAccess = projectAccess;
        this.notificationEventMapper = notificationEventMapper;
        this.teamEventMapper = teamEventMapper;
        this.realtimeHub = realtimeHub;
        this.projectMemberMapper = projectMemberMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.groupMapper = groupMapper;
        this.projectMapper = projectMapper;
        this.userMapper = userMapper;
        this.teamMapper = teamMapper;
        this.eventTransaction = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        if (this.eventTransaction != null) {
            // 事件不能加入已经写入 AgentRun 的业务事务。死锁重试必须从完整的新事务开始。
            this.eventTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
        this.metrics = metrics;
        // SSE 泵线程执行期间保留提交线程的 MDC（requestId），便于按请求串联日志
        MdcTaskDecorator mdcDecorator = new MdcTaskDecorator();
        AtomicInteger seq = new AtomicInteger(1);
        ThreadFactory factory = r -> {
            Thread t = new Thread(mdcDecorator.decorate(r), "sse-event-pump-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        // 每连接占用一个轮询线程；规模扩大后应改为共享调度器或推模式
        this.executor = Executors.newFixedThreadPool(8, factory);
        if (metrics != null) {
            Gauge.builder("qgents.sse.connections", activeSseConnections, AtomicInteger::get)
                    .description("当前活跃的项目 SSE 连接数")
                    .register(metrics.registry());
        }
    }

    /** 单元测试构造器；生产装配使用包含用户、团队锁 Mapper 的构造器。 */
    public EventService(EventMapper eventMapper, ProjectAccessService projectAccess,
                        NotificationEventMapper notificationEventMapper, TeamEventMapper teamEventMapper,
                        RealtimeHub realtimeHub,
                        ProjectMemberMapper projectMemberMapper, TeamMemberMapper teamMemberMapper,
                        ChatGroupMapper groupMapper,
                        ProjectMapper projectMapper) {
        this(eventMapper, projectAccess, notificationEventMapper, teamEventMapper, realtimeHub,
                projectMemberMapper, teamMemberMapper, groupMapper, projectMapper,
                null, null, null, null);
    }

    /** 单元测试构造器，不配置事务重试。 */
    public EventService(EventMapper eventMapper, ProjectAccessService projectAccess,
                        NotificationEventMapper notificationEventMapper, TeamEventMapper teamEventMapper,
                        RealtimeHub realtimeHub,
                        ProjectMemberMapper projectMemberMapper, TeamMemberMapper teamMemberMapper,
                        ChatGroupMapper groupMapper,
                        ProjectMapper projectMapper, UserMapper userMapper, TeamMapper teamMapper) {
        this(eventMapper, projectAccess, notificationEventMapper, teamEventMapper, realtimeHub,
                projectMemberMapper, teamMemberMapper, groupMapper, projectMapper,
                userMapper, teamMapper, null, null);
    }

    /** 业务事务提交后，独立保存项目事件并推送；事件失败不回滚业务。 */
    public void publish(UUID projectId, UUID groupId, String eventType, String resourceId,
                        Map<String, Object> payload) {
        EventRequest request = new EventRequest(projectId, groupId, eventType, resourceId, payload);
        // 业务事实提交后才允许事件落库。这样 events 的行锁、外键检查或重试耗尽都不会把
        // AgentRun 等已经完成的业务写入标记为 rollback-only。
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            enqueueAfterCommit(request);
            return;
        }
        persistAfterCommit(request);
    }

    /** 将同一业务事务中的事件按调用顺序批量挂到唯一的 afterCommit 回调。 */
    private void enqueueAfterCommit(EventRequest request) {
        EventQueue queue = (EventQueue) TransactionSynchronizationManager.getResource(projectEventQueueResource);
        if (queue == null) {
            queue = new EventQueue();
            TransactionSynchronizationManager.bindResource(projectEventQueueResource, queue);
            EventQueue queued = queue;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // 单条事件的失败已经在 persistAfterCommit 中被隔离；继续投递同批后续事件。
                    for (EventRequest item : queued.requests) {
                        persistAfterCommit(item);
                    }
                }

                @Override
                public void afterCompletion(int status) {
                    if (TransactionSynchronizationManager.hasResource(projectEventQueueResource)) {
                        TransactionSynchronizationManager.unbindResource(projectEventQueueResource);
                    }
                }
            });
        }
        queue.requests.add(request);
    }

    /** 使用全新短事务写入一条已提交业务事实对应的 SSE 事件，并在成功提交后 fan-out。 */
    private void persistAfterCommit(EventRequest request) {
        for (int attempt = 0; attempt < EVENT_DEADLOCK_RETRIES; attempt++) {
            try {
                Set<UUID> recipients = eventTransaction == null
                        ? persistInNewTransaction(request)
                        : eventTransaction.execute(status -> persistInNewTransaction(request));
                if (recipients != null) {
                    // TransactionTemplate 已在返回前完成 commit；不得让浏览器看到领先于数据库的刷新信号。
                    broadcast(recipients, "project", id(request.projectId), id(request.groupId), null, null,
                            request.resourceId, request.eventType, request.payload);
                }
                return;
            } catch (RuntimeException failure) {
                boolean deadlock = isDeadlock(failure);
                if (!deadlock || attempt + 1 >= EVENT_DEADLOCK_RETRIES) {
                    if (metrics != null) {
                        metrics.increment("qgents.sse.event.persist.failures", "event_persist",
                                deadlock ? "deadlock_exhausted" : "failed");
                    }
                    // 事件只承担前端刷新提示；业务事实已经提交，不能因其失败被误报为 Agent 失败。
                    log.warn("project event persistence skipped projectId={} eventType={} attempts={} exceptionType={}",
                            request.projectId, request.eventType, attempt + 1, failure.getClass().getSimpleName());
                    return;
                }
                long backoff = EVENT_DEADLOCK_BACKOFF_MS << attempt;
                if (metrics != null) {
                    metrics.increment("qgents.sse.event.persist.retries", "event_persist", "deadlock_retry");
                }
                log.warn("event publish deadlocked; retrying projectId={} eventType={} attempt={} backoffMs={}",
                        request.projectId, request.eventType, attempt + 1, backoff);
                if (!sleepBackoff(backoff)) {
                    if (metrics != null) {
                        metrics.increment("qgents.sse.event.persist.failures", "event_persist", "interrupted");
                    }
                    log.warn("project event persistence interrupted projectId={} eventType={} attempts={}",
                            request.projectId, request.eventType, attempt + 1);
                    return;
                }
            }
        }
    }

    /** 在 REQUIRES_NEW 事务中分配项目序号、写入事件并返回提交后 fan-out 所需的信息。 */
    private Set<UUID> persistInNewTransaction(EventRequest request) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        EventEntity event = new EventEntity();
        event.setId(UuidV7.next());
        event.setProjectId(request.projectId);
        event.setGroupId(request.groupId);
        // 并发安全：先持有项目行锁，把同项目事件写入串行化；再以锁定读取当前已提交的
        // 最大序号 + 1。缺少项目行锁时，REPEATABLE READ 的普通快照 MAX 会读到旧值，
        // 多个并发发布撞 uk_event_seq 唯一键 → 整体 500（群成员多选邀请等场景）。
        projectMapper.selectByIdForUpdate(request.projectId);
        event.setSequenceNo(eventMapper.nextSequence(request.projectId));
        event.setEventType(request.eventType);
        event.setResourceId(request.resourceId);
        event.setPayload(request.payload);
        event.setCreatedAt(now);
        eventMapper.insert(event);
        if (request.groupId != null) {
            var group = groupMapper.selectById(request.groupId);
            if (group != null && Boolean.FALSE.equals(group.getDefaultGroup())) {
                return Set.copyOf(groupMapper.selectUserIds(request.groupId));
            }
        }
        return projectMembers(request.projectId);
    }

    private boolean isDeadlock(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof DeadlockLoserDataAccessException) {
                return true;
            }
            if (current instanceof SQLException sql
                    && ("40001".equals(sql.getSQLState()) || sql.getErrorCode() == 1213)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean sleepBackoff(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Set<UUID> projectMembers(UUID projectId) {
        return projectMemberMapper.selectMembers(projectId).stream()
                .map(m -> m.getUserId()).collect(Collectors.toSet());
    }

    /** 发布一条通知级事件。 按用户维度分配单调递增序号；payload 必须为已脱敏内容。 */
    @Transactional
    public void publishNotification(UUID recipientUserId, UUID notificationId, String eventType,
                                    Map<String, Object> payload) {
        if (recipientUserId == null) {
            return;
        }
        if (userMapper != null) {
            userMapper.selectByIdForUpdate(recipientUserId);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        NotificationEventEntity event = new NotificationEventEntity();
        event.setId(UuidV7.next());
        event.setRecipientUserId(recipientUserId);
        event.setSequenceNo(notificationEventMapper.maxSequence(recipientUserId) + 1);
        event.setNotificationId(notificationId);
        event.setKind(eventType);
        event.setPayload(payload);
        event.setCreatedAt(now);
        notificationEventMapper.insert(event);
        // WebSocket 用户级聚合：仅推送该接收用户（其全部在线连接都收到）
        fan(Set.of(recipientUserId), "notification", null, null, null, id(recipientUserId), id(notificationId),
                eventType, payload);
    }

    /** 订阅当前用户的通知事件，支持 Last-Event-ID 续传。 */
    public SseEmitter notificationStream(UUID userId, Long lastEventId) {
        long cursor = resolveCursor(lastEventId, () -> notificationEventMapper.minSequence(userId),
                () -> notificationEventMapper.maxSequence(userId));
        return openStream("notification", userId, cursor, after ->
                notificationEventMapper.listAfter(userId, after, BATCH_SIZE).stream()
                        .map(event -> new SseEvent(event.getSequenceNo(), notificationEventType(event.getKind()),
                                event.getPayload(), true)).toList(), false);
    }

    /** 发布一条团队级事件。 按团队维度分配单调递增序号；payload 必须为已脱敏内容。 */
    @Transactional
    public void publishTeamEvent(UUID teamId, String eventType, String resourceId, Map<String, Object> payload) {
        if (teamId == null) {
            return;
        }
        if (teamMapper != null) {
            teamMapper.selectByIdForUpdate(teamId);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        TeamEventEntity event = new TeamEventEntity();
        event.setId(UuidV7.next());
        event.setTeamId(teamId);
        event.setSequenceNo(teamEventMapper.maxSequence(teamId) + 1);
        event.setEventType(eventType);
        event.setResourceId(resourceId);
        event.setPayload(payload);
        event.setCreatedAt(now);
        teamEventMapper.insert(event);
        // WebSocket 用户级聚合：推送给本团队全部成员（其所有在线连接都收到）
        Set<UUID> members = teamMemberMapper.selectByTeamId(teamId).stream()
                .map(m -> m.getUserId()).collect(Collectors.toSet());
        fan(members, "team", null, null, id(teamId), null, resourceId, eventType, payload);
    }

    /** 订阅团队事件；调用者必须是团队成员。 */
    public SseEmitter teamStream(UUID teamId, UUID userId, Long lastEventId) {
        projectAccess.requireTeamMember(teamId, userId);
        long cursor = resolveCursor(lastEventId, () -> teamEventMapper.minSequence(teamId),
                () -> teamEventMapper.maxSequence(teamId));
        return openStream("team", teamId, cursor, after ->
                teamEventMapper.listAfter(teamId, after, BATCH_SIZE).stream()
                        .map(event -> new SseEvent(event.getSequenceNo(), event.getEventType(),
                                event.getPayload(), true)).toList(), false);
    }

    /** 订阅项目事件；保留项目群聊过滤和轮询耗时统计。 */
    public SseEmitter stream(UUID projectId, UUID userId, Long lastEventId) {
        projectAccess.requireProjectMember(projectId, userId);
        long cursor = resolveCursor(lastEventId, () -> eventMapper.minSequence(projectId),
                () -> eventMapper.maxSequence(projectId));
        return openStream("project", projectId, cursor, after ->
                eventMapper.listAfter(projectId, after, BATCH_SIZE).stream()
                        .map(event -> new SseEvent(event.getSequenceNo(), event.getEventType(), event.getPayload(),
                                event.getGroupId() == null || canSeeGroup(event.getGroupId(), userId)))
                        .toList(), true);
    }

    /** 每次读取事件时核对成员，已有连接也能收到刚创建或刚被邀请的群事件。 */
    private boolean canSeeGroup(UUID groupId, UUID userId) {
        ChatGroupEntity group = groupMapper.selectById(groupId);
        return group != null && (Boolean.TRUE.equals(group.getDefaultGroup())
                || groupMapper.selectUserIds(groupId).contains(userId));
    }

    /** 不传游标时从最新事件开始；无效或过期的续传点拒绝连接。 */
    private long resolveCursor(Long lastEventId, Supplier<Long> minimum, Supplier<Long> maximum) {
        if (lastEventId == null) return maximum.get();
        if (lastEventId < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EVENT_CURSOR", "Last-Event-ID 必须为非负序号");
        }
        Long min = minimum.get();
        if (min == null || min > lastEventId) {
            throw new ApiException(HttpStatus.CONFLICT, "EVENT_CURSOR_EXPIRED", "续传点已过期，请重新拉取相关资源");
        }
        return lastEventId;
    }

    /** 三类事件共用连接计数、关闭回调和轮询；业务查询由调用方提供。 */
    private SseEmitter openStream(String scope, UUID ownerId, long cursor,
                                 LongFunction<List<SseEvent>> loadEvents, boolean measurePoll) {
        if (!acquireSseSlot()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SSE_CONNECTION_LIMIT_EXCEEDED",
                    "当前实时连接过多，请稍后重试");
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean connectionClosed = new AtomicBoolean();
        emitter.onTimeout(() -> {
            closeSseConnection(connectionClosed);
            log.info("SSE {} timeout, ownerId={}, cursor={}", scope, ownerId, cursor);
        });
        emitter.onError(error -> {
            closeSseConnection(connectionClosed);
            log.info("SSE {} error, ownerId={}: {}", scope, ownerId, error.getMessage());
        });
        emitter.onCompletion(() -> {
            closeSseConnection(connectionClosed);
            log.info("SSE {} completed, ownerId={}, cursor={}", scope, ownerId, cursor);
        });
        executor.execute(() -> pump(emitter, cursor, loadEvents, measurePoll, connectionClosed));
        return emitter;
    }

    /** 每两秒拉取增量，每十五秒发送心跳；跳过不可见事件时仍推进游标。 */
    private void pump(SseEmitter emitter, long cursor, LongFunction<List<SseEvent>> loadEvents,
                      boolean measurePoll, AtomicBoolean connectionClosed) {
        LocalDateTime lastHeartbeat = LocalDateTime.now(ZoneOffset.UTC);
        try {
            while (!connectionClosed.get()) {
                Timer.Sample pollTimer = measurePoll && metrics != null ? metrics.start() : null;
                List<SseEvent> events = loadEvents.apply(cursor);
                if (measurePoll && metrics != null) {
                    metrics.stop(pollTimer, "qgents.sse.poll.duration", "event_poll", "succeeded");
                }
                for (SseEvent event : events) {
                    if (event.visible && !send(emitter, SseEmitter.event()
                            .id(String.valueOf(event.sequenceNo)).name(event.type).data(event.payload))) {
                        return;
                    }
                    cursor = event.sequenceNo;
                }
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                if (Duration.between(lastHeartbeat, now).getSeconds() >= HEARTBEAT_SECONDS) {
                    if (!send(emitter, SseEmitter.event().comment("heartbeat"))) return;
                    lastHeartbeat = now;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeSseConnection(connectionClosed);
        }
    }

    private record SseEvent(long sequenceNo, String type, Map<String, Object> payload, boolean visible) {
    }

    /** 通知 SSE 使用 notification.* 事件名；业务通知类型统一映射为 notification.created。 */
    String notificationEventType(String kind) {
        return kind != null && kind.startsWith("notification.") ? kind : "notification.created";
    }

    private boolean send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            if (metrics != null) {
                metrics.increment("qgents.sse.send.failures", "event_send", "failed");
            }
            // 客户端断开/连接失效（含 AsyncRequestNotUsableException，其亦为 IOException 子类）：
            // 立即结束该 emitter，泵线程退出，等待客户端按 Last-Event-ID 重连
            completeQuietly(emitter);
            return false;
        }
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 连接已断时 complete 自身也可能抛错，忽略
        }
    }

    /**
     * 尝试占用一个 SSE 连接槽位。超过 {@link #MAX_SSE_CONNECTIONS} 时返回 false， 调用方应拒绝建立新连接（429），避免连接堆积耗尽泵线程（固定 8 线程池排队） 与数据库连接（每个泵线程周期性查询事件表）。
     */
    private boolean acquireSseSlot() {
        while (true) {
            int current = activeSseConnections.get();
            if (current >= MAX_SSE_CONNECTIONS) {
                log.warn("SSE connection limit reached, active={} max={}", current, MAX_SSE_CONNECTIONS);
                return false;
            }
            if (activeSseConnections.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void closeSseConnection(AtomicBoolean connectionClosed) {
        if (connectionClosed.compareAndSet(false, true)) {
            activeSseConnections.decrementAndGet();
        }
    }

    /** 提交后向目标用户的全部在线连接推送；失败只记日志。 */
    private void fan(Set<UUID> userIds, String scope, String projectId, String groupId, String teamId,
                     String recipientUserId, String resourceId, String eventType, Map<String, Object> payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 事务提交前浏览器若据 WS 帧立即查询，可能读不到尚未提交的事件/消息。
            // 只在提交成功后 fan-out，回滚时不发布易失刷新信号。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    broadcast(userIds, scope, projectId, groupId, teamId, recipientUserId, resourceId,
                            eventType, payload);
                }
            });
            return;
        }
        broadcast(userIds, scope, projectId, groupId, teamId, recipientUserId, resourceId, eventType, payload);
    }

    private void broadcast(Set<UUID> userIds, String scope, String projectId, String groupId, String teamId,
                           String recipientUserId, String resourceId, String eventType, Map<String, Object> payload) {
        try {
            realtimeHub.broadcastToUsers(userIds, RealtimeFrame.of(eventType, scope, projectId, groupId,
                    teamId, recipientUserId, resourceId, payload));
        } catch (Exception e) {
            log.warn("realtime fan-out failed, scope={}, eventType={}: {}", scope, eventType, e.getMessage());
        }
    }

    private String id(UUID value) {
        return value == null ? null : value.toString();
    }

    /** 单条待持久化的项目 SSE 事件；仅在当前线程的事务同步生命周期内保存。 */
    private record EventRequest(UUID projectId, UUID groupId, String eventType, String resourceId,
                                Map<String, Object> payload) {
    }

    /** 同一业务事务内按调用顺序投递的项目事件集合。 */
    private static final class EventQueue {
        private final List<EventRequest> requests = new ArrayList<>();
    }

    /** 每日清理超过 24 小时的项目事件，与业务事务分开执行。 */
    @Scheduled(cron = "0 15 3 * * *")
    public void purgeExpired() {
        try {
            int removed = eventMapper.deleteExpired(LocalDateTime.now(ZoneOffset.UTC).minusHours(RETENTION_HOURS));
            if (removed > 0) {
                log.info("purged {} expired events", removed);
            }
        } catch (Exception e) {
            log.warn("event purge failed: {}", e.getMessage());
        }
    }
}

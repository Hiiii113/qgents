package qg.qgent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.service.AgentService;
import qg.qgent.chat.dto.GroupCreateRequest;
import qg.qgent.chat.dto.GroupResponse;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.service.GroupService;
import qg.qgent.harness.entity.AgentRunToolEntity;
import qg.qgent.harness.entity.RunStatus;
import qg.qgent.harness.mapper.AgentRunToolMapper;
import qg.qgent.harness.model.ModelClient;
import qg.qgent.harness.runtime.AgentChatService;
import qg.qgent.harness.runtime.AgentLoop;
import qg.qgent.harness.service.AgentRunService;
import qg.qgent.memory.service.AgentMemoryService;
import qg.qgent.memory.service.GroupMemoryService;
import qg.qgent.memory.service.ProjectMemoryService;
import qg.qgent.project.dto.ProjectCreateRequest;
import qg.qgent.project.dto.ProjectResponse;
import qg.qgent.project.service.ProjectService;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.team.dto.TeamCreateRequest;
import qg.qgent.team.dto.TeamResponse;
import qg.qgent.team.service.TeamService;
import qg.qgent.user.entity.UserEntity;
import qg.qgent.user.mapper.UserMapper;
import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.service.WorkspaceService;

/**
 * 评测主调度器：读取用例 -> 初始化隔离项目与基线 -> 调用 AgentChatService.runTask -> 判分 -> 输出报告。
 */
@Service
@RequiredArgsConstructor
public class EvalRunner {
    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);
    private static final DateTimeFormatter BATCH_TIME =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final UserMapper userMapper;
    private final TeamService teamService;
    private final ProjectService projectService;
    private final GroupService groupService;
    private final AgentService agentService;
    private final MessageMapper messageMapper;
    private final ProjectMemoryService projectMemoryService;
    private final GroupMemoryService groupMemoryService;
    private final AgentMemoryService agentMemoryService;
    private final WorkspaceService workspaceService;
    private final ProjectDirectoryService directoryService;
    private final AgentChatService agentChatService;
    private final AgentRunService agentRunService;
    private final AgentRunToolMapper agentRunToolMapper;
    private final ModelClient modelClient;
    private final ObjectMapper objectMapper;

    public record Options(
        Path evalsDir,
        Path outputRoot,
        String suite,
        String caseId,
        Integer repeatOverride,
        Integer timeoutOverride,
        String profileName,
        Path compareBaseline,
        ModelClient judgeModel) {}

    public EvalReport.SuiteReport run(Options options) throws IOException {
        Path evalsDir = options.evalsDir();
        EvalModelConfig modelConfig =
            EvalModelConfig.load(evalsDir.resolve("models.yaml"), objectMapper);
        List<EvalCase> allCases = EvalCase.loadAll(evalsDir.resolve("cases"), objectMapper);
        List<EvalCase> selected =
            allCases.stream()
                .filter(c -> options.caseId() == null || options.caseId().equals(c.getId()))
                .filter(
                    c ->
                        options.suite() == null
                            || "all".equalsIgnoreCase(options.suite())
                            || options.suite().equalsIgnoreCase(c.getSuite()))
                .toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException(
                "未匹配到评测用例 (suite=" + options.suite() + ", case=" + options.caseId() + ")");
        }

        String batchId = BATCH_TIME.format(Instant.now()) + "-" + sanitize(modelClient.modelName());
        List<EvalReport.AttemptReport> attempts = new ArrayList<>();
        for (EvalCase evalCase : selected) {
            int repeats =
                options.repeatOverride() != null && options.repeatOverride() > 0
                    ? options.repeatOverride()
                    : Math.max(1, evalCase.getRepeat());
            for (int attempt = 1; attempt <= repeats; attempt++) {
                log.info("Running eval case={} attempt={}/{}", evalCase.getId(), attempt, repeats);
                attempts.add(runAttempt(evalCase, attempt, options, modelConfig));
            }
        }

        String modelId = modelClient.modelName() == null ? "unknown" : modelClient.modelName();
        String baseUrl = modelClient.baseUrl() == null ? "" : modelClient.baseUrl();
        String currency = modelConfig.currency(modelId, options.profileName());
        EvalReport.SuiteReport suiteReport =
            EvalReport.summarize(batchId, modelId, baseUrl, currency, attempts);
        EvalReport.SuiteReport baseline =
            options.compareBaseline() != null
                ? EvalReport.readSuite(options.compareBaseline(), objectMapper)
                : null;
        Path batchDir = options.outputRoot().resolve(batchId);
        EvalReport.writeAll(batchDir, suiteReport, attempts, baseline, objectMapper);
        System.out.println(EvalReport.renderMarkdown(suiteReport, baseline));
        System.out.println("Report saved to: " + batchDir.toAbsolutePath());
        return suiteReport;
    }

    private EvalReport.AttemptReport runAttempt(
        EvalCase evalCase, int attempt, Options options, EvalModelConfig modelConfig) {
        String modelId = modelClient.modelName() == null ? "unknown" : modelClient.modelName();
        String baseUrl = modelClient.baseUrl() == null ? "" : modelClient.baseUrl();
        String currency = modelConfig.currency(modelId, options.profileName());
        long wallStart = System.nanoTime();
        Long lastRunId = null;
        String lastRunStatus = "UNKNOWN";
        int totalRounds = 0;
        AgentLoop.Metrics totalMetrics = AgentLoop.Metrics.ZERO;
        List<AgentRunToolEntity> allTools = new ArrayList<>();
        String finalResponse = "";
        int timeoutSec =
            options.timeoutOverride() != null && options.timeoutOverride() > 0
                ? options.timeoutOverride()
                : Math.max(10, evalCase.getTimeoutSeconds());

        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        try {
            SeededEnv env = seedEnvironment(evalCase, options.evalsDir());
            Map<String, String> initialContents = snapshotMemories(env);
            AtomicBoolean timedOut = new AtomicBoolean(false);

            List<EvalCase.Turn> turns = evalCase.effectiveTurns();
            for (int i = 0; i < turns.size(); i++) {
                EvalCase.Turn turn = turns.get(i);
                Long actorId =
                    "PROJECT_MEMBER".equalsIgnoreCase(turn.getActorRole())
                        ? env.memberUserId()
                        : env.adminUserId();
                AgentChatService.TaskRun task =
                    new AgentChatService.TaskRun(
                        env.agentId(),
                        env.projectId(),
                        env.defaultGroupId(),
                        actorId,
                        null,
                        turn.isDirect());
                // 单轮前把用户指令写进群历史（非最后一条），让多轮对话里的后续轮次能看到上一轮交互。
                final Thread worker = Thread.currentThread();
                ScheduledFuture<?> timeoutTask =
                    timer.schedule(
                        () -> {
                            timedOut.set(true);
                            worker.interrupt();
                        },
                        timeoutSec,
                        TimeUnit.SECONDS);
                AgentChatService.Outcome outcome;
                try {
                    outcome = agentChatService.runTask(task, turn.getInstruction());
                } finally {
                    timeoutTask.cancel(false);
                }
                lastRunId = outcome.runId();
                lastRunStatus = outcome.status().name();
                finalResponse = outcome.text();
                totalRounds += outcome.rounds();
                totalMetrics = totalMetrics.plus(outcome.metrics());
                allTools.addAll(agentRunToolMapper.byRunIds(List.of(outcome.runId())));
                if (timedOut.get()) {
                    agentRunService.cancelRoot(outcome.runId());
                    long wallMs = elapsedMs(wallStart);
                    return new EvalReport.AttemptReport(
                        evalCase.getId(),
                        evalCase.getSuite(),
                        attempt,
                        lastRunId,
                        RunStatus.CANCELLED.name(),
                        EvalReport.Verdict.TIMEOUT,
                        modelId,
                        baseUrl,
                        wallMs,
                        totalMetrics.ttftMs(),
                        totalMetrics.modelTimeMs(),
                        totalMetrics.toolTimeMs(),
                        totalRounds,
                        totalMetrics.toolCalls(),
                        totalMetrics.failedToolCalls(),
                        totalMetrics.promptTokens(),
                        totalMetrics.cachedTokens(),
                        totalMetrics.completionTokens(),
                        totalMetrics.reasoningTokens(),
                        modelConfig.estimateCost(modelId, options.profileName(), totalMetrics),
                        currency,
                        finalResponse,
                        List.of(),
                        List.of("超过运行时限 " + timeoutSec + "s，已触发取消"));
                }
                if (i + 1 < turns.size()) {
                    appendHistory(env.defaultGroupId(), env.nextSeq(), actorId, null, "USER", turn.getInstruction());
                    appendHistory(env.defaultGroupId(), env.nextSeq(), null, env.agentId(), "AGENT", finalResponse);
                }
            }

            Map<String, String> finalContents = snapshotMemories(env);
            EvalGrader.GradingResult graded =
                EvalGrader.grade(
                    evalCase,
                    new EvalGrader.GradingContext(
                        finalResponse,
                        allTools,
                        initialContents,
                        finalContents,
                        env.codingRepoDir(),
                        env.baseSha(),
                        options.evalsDir(),
                        evalCase.getInitialState().getCodingRepo(),
                        options.judgeModel()));
            long wallMs = elapsedMs(wallStart);
            return new EvalReport.AttemptReport(
                evalCase.getId(),
                evalCase.getSuite(),
                attempt,
                lastRunId,
                lastRunStatus,
                graded.verdict(),
                modelId,
                baseUrl,
                wallMs,
                totalMetrics.ttftMs(),
                totalMetrics.modelTimeMs(),
                totalMetrics.toolTimeMs(),
                totalRounds,
                totalMetrics.toolCalls(),
                totalMetrics.failedToolCalls(),
                totalMetrics.promptTokens(),
                totalMetrics.cachedTokens(),
                totalMetrics.completionTokens(),
                totalMetrics.reasoningTokens(),
                modelConfig.estimateCost(modelId, options.profileName(), totalMetrics),
                currency,
                finalResponse,
                graded.checks(),
                graded.evidence());
        } catch (Exception exception) {
            log.error("Eval framework error on case={} attempt={}", evalCase.getId(), attempt, exception);
            long wallMs = elapsedMs(wallStart);
            return new EvalReport.AttemptReport(
                evalCase.getId(),
                evalCase.getSuite(),
                attempt,
                lastRunId,
                lastRunStatus,
                EvalReport.Verdict.ERROR,
                modelId,
                baseUrl,
                wallMs,
                totalMetrics.ttftMs(),
                totalMetrics.modelTimeMs(),
                totalMetrics.toolTimeMs(),
                totalRounds,
                totalMetrics.toolCalls(),
                totalMetrics.failedToolCalls(),
                totalMetrics.promptTokens(),
                totalMetrics.cachedTokens(),
                totalMetrics.completionTokens(),
                totalMetrics.reasoningTokens(),
                modelConfig.estimateCost(modelId, options.profileName(), totalMetrics),
                currency,
                finalResponse,
                List.of(),
                List.of("评测环境或判分器异常: " + exception.getClass().getSimpleName() + ": " + exception.getMessage()));
        } finally {
            timer.shutdownNow();
        }
    }

    private SeededEnv seedEnvironment(EvalCase evalCase, Path evalsDir)
        throws IOException, InterruptedException {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Long adminId = createUser("eval-admin-" + tag + "@qgents.local", "EvalAdmin");
        Long memberId = createUser("eval-member-" + tag + "@qgents.local", "EvalMember");
        TeamCreateRequest teamReq = new TeamCreateRequest();
        teamReq.setName("EvalTeam-" + tag);
        TeamResponse team = teamService.create(adminId, teamReq);
        Long teamId = Long.parseLong(team.getId());

        ProjectCreateRequest projectReq = new ProjectCreateRequest();
        projectReq.setName("EvalProject-" + evalCase.getId() + "-" + tag);
        projectReq.setDescription("Automated eval run");
        ProjectResponse project = projectService.create(adminId, teamId, projectReq);
        Long projectId = Long.parseLong(project.getId());

        GroupResponse defaultGroup = groupService.ensureProjectGroup(projectId, adminId, project.getName());
        Long defaultGroupId = Long.parseLong(defaultGroup.getId());
        AgentEntity agent = agentService.defaultAgent(projectId);
        EvalCase.InitialState state = evalCase.getInitialState();
        if (state != null) {
            if (state.getProjectMemory() != null && !state.getProjectMemory().isBlank()) {
                projectMemoryService.update(adminId, projectId, resolveText(evalsDir, state.getProjectMemory()));
            }
            if (state.getGroupMemory() != null && !state.getGroupMemory().isBlank()) {
                groupMemoryService.write(
                    projectId, defaultGroupId, resolveText(evalsDir, state.getGroupMemory()));
            }
            if (state.getAgentMemory() != null && !state.getAgentMemory().isBlank()) {
                agentMemoryService.save(
                    projectId, agent.getId(), resolveText(evalsDir, state.getAgentMemory()));
            }
        }

        long[] seq = {0L};
        if (state != null && state.getGroupHistory() != null) {
            for (EvalCase.HistoryMessage msg : state.getGroupHistory()) {
                boolean isAgent = "AGENT".equalsIgnoreCase(msg.getSender());
                appendHistory(
                    defaultGroupId,
                    ++seq[0],
                    isAgent ? null : adminId,
                    isAgent ? agent.getId() : null,
                    isAgent ? "AGENT" : "USER",
                    msg.getText());
            }
        }

        if (state != null && state.getOtherGroups() != null) {
            for (EvalCase.OtherGroupSpec other : state.getOtherGroups()) {
                Long creator = other.isRequesterInGroup() ? adminId : memberId;
                GroupCreateRequest req = new GroupCreateRequest();
                req.setTitle(other.getName());
                if (other.isAgentInGroup()) {
                    req.setAgentIds(List.of(agent.getId()));
                }
                if (other.isRequesterInGroup() && !creator.equals(adminId)) {
                    req.setUserIds(List.of(adminId));
                }
                GroupResponse created = groupService.create(creator, projectId, req);
                Long otherGroupId = Long.parseLong(created.getId());
                if (other.getGroupMemory() != null && !other.getGroupMemory().isBlank()) {
                    groupMemoryService.write(
                        projectId, otherGroupId, resolveText(evalsDir, other.getGroupMemory()));
                }
                long otherSeq = 0;
                for (EvalCase.HistoryMessage msg : other.getHistory()) {
                    boolean isAgent = "AGENT".equalsIgnoreCase(msg.getSender());
                    appendHistory(
                        otherGroupId,
                        ++otherSeq,
                        isAgent ? null : creator,
                        isAgent ? agent.getId() : null,
                        isAgent ? "AGENT" : "USER",
                        msg.getText());
                }
            }
        }

        Path codingRepoDir = null;
        String baseSha = null;
        if (state != null && state.getCodingRepo() != null) {
            WorkspaceEntity ws = workspaceService.defaultWorkspace(projectId);
            Path wsDir = directoryService.workspace(projectId, WorkspaceService.directoryName(ws));
            codingRepoDir = wsDir.resolve(state.getCodingRepo().getTargetDir());
            baseSha =
                LocalCodingRepoMaterializer.materializeRepo(
                    evalsDir.resolve(state.getCodingRepo().getFixtureDir()), codingRepoDir);
        }

        return new SeededEnv(
            adminId, memberId, projectId, defaultGroupId, agent.getId(), codingRepoDir, baseSha, seq);
    }

    private Map<String, String> snapshotMemories(SeededEnv env) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("project_memory", projectMemoryService.get(env.adminUserId(), env.projectId()).getContent());
        map.put("group_memory", groupMemoryService.read(env.projectId(), env.defaultGroupId()).getContent());
        map.put("agent_memory", agentMemoryService.view(env.projectId(), env.agentId()));
        return map;
    }

    private Long createUser(String email, String displayName) {
        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setDisplayName(displayName);
        user.setPasswordHash("eval-noop");
        user.setStatus("ACTIVE");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        return user.getId();
    }

    private void appendHistory(
        Long groupId,
        long sequenceNo,
        Long authorUserId,
        Long senderAgentId,
        String senderType,
        String text)
        throws IOException {
        MessageEntity msg = new MessageEntity();
        msg.setGroupId(groupId);
        msg.setSequenceNo(sequenceNo);
        msg.setAuthorUserId(authorUserId);
        msg.setSenderAgentId(senderAgentId);
        msg.setSenderType(senderType);
        msg.setMessageType("TEXT");
        msg.setContent(objectMapper.writeValueAsString(Map.of("text", text == null ? "" : text)));
        msg.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        messageMapper.insert(msg);
    }

    private static String resolveText(Path evalsDir, String value) throws IOException {
        Path candidate = evalsDir.resolve(value);
        if (Files.isRegularFile(candidate)) {
            return Files.readString(candidate);
        }
        return value;
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return "model";
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private record SeededEnv(
        Long adminUserId,
        Long memberUserId,
        Long projectId,
        Long defaultGroupId,
        Long agentId,
        Path codingRepoDir,
        String baseSha,
        long[] sequenceCounter) {
        long nextSeq() {
            return ++sequenceCounter[0];
        }
    }
}

package qg.qgent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 评测结果结构、JSON 落盘与 Markdown 汇总/基线对比渲染。
 */
public final class EvalReport {

    public enum Verdict {
        PASS,
        FAIL,
        TIMEOUT,
        ERROR
    }

    public record CheckResult(String kind, boolean passed, String detail) {}

    public record AttemptReport(
        String caseId,
        String suite,
        int attempt,
        Long rootRunId,
        String runStatus,
        Verdict verdict,
        String model,
        String baseUrl,
        long wallClockMs,
        Long ttftMs,
        long modelTimeMs,
        long toolTimeMs,
        int rounds,
        int toolCalls,
        int failedToolCalls,
        long promptTokens,
        long cachedTokens,
        long completionTokens,
        long reasoningTokens,
        double estimatedCost,
        String currency,
        String finalResponse,
        List<CheckResult> checks,
        List<String> evidence) {}

    public record CaseSummary(
        String caseId,
        String suite,
        int attempts,
        int passed,
        int failed,
        int timedOut,
        int errored,
        double passRate,
        double avgWallClockMs,
        double avgTtftMs,
        double avgRounds,
        double totalCost,
        Double costPerPass) {}

    public record SuiteReport(
        String batchId,
        String createdAt,
        String model,
        String baseUrl,
        String currency,
        int totalAttempts,
        int passed,
        int failed,
        int timedOut,
        int errored,
        double passRate,
        double totalCost,
        Double costPerPass,
        double avgWallClockMs,
        double p50WallClockMs,
        Double avgTtftMs,
        double avgModelTimeMs,
        double avgToolTimeMs,
        double avgRounds,
        long totalPromptTokens,
        long totalCachedTokens,
        long totalCompletionTokens,
        double cacheHitRate,
        List<CaseSummary> cases) {}

    private EvalReport() {}

    public static SuiteReport summarize(
        String batchId,
        String model,
        String baseUrl,
        String currency,
        List<AttemptReport> attempts) {
        Map<String, List<AttemptReport>> byCase = new LinkedHashMap<>();
        for (AttemptReport attempt : attempts) {
            byCase.computeIfAbsent(attempt.caseId(), ignored -> new ArrayList<>()).add(attempt);
        }
        List<CaseSummary> caseSummaries = new ArrayList<>();
        for (Map.Entry<String, List<AttemptReport>> entry : byCase.entrySet()) {
            List<AttemptReport> list = entry.getValue();
            int pass = (int) list.stream().filter(a -> a.verdict() == Verdict.PASS).count();
            int fail = (int) list.stream().filter(a -> a.verdict() == Verdict.FAIL).count();
            int timeout = (int) list.stream().filter(a -> a.verdict() == Verdict.TIMEOUT).count();
            int error = (int) list.stream().filter(a -> a.verdict() == Verdict.ERROR).count();
            double cost = list.stream().mapToDouble(AttemptReport::estimatedCost).sum();
            double avgWall = list.stream().mapToLong(AttemptReport::wallClockMs).average().orElse(0);
            double avgTtft =
                list.stream()
                    .filter(a -> a.ttftMs() != null)
                    .mapToLong(AttemptReport::ttftMs)
                    .average()
                    .orElse(-1);
            double avgRounds = list.stream().mapToInt(AttemptReport::rounds).average().orElse(0);
            caseSummaries.add(
                new CaseSummary(
                    entry.getKey(),
                    list.getFirst().suite(),
                    list.size(),
                    pass,
                    fail,
                    timeout,
                    error,
                    list.isEmpty() ? 0 : (double) pass / list.size(),
                    avgWall,
                    avgTtft < 0 ? 0 : avgTtft,
                    avgRounds,
                    cost,
                    pass == 0 ? null : cost / pass));
        }

        int total = attempts.size();
        int pass = (int) attempts.stream().filter(a -> a.verdict() == Verdict.PASS).count();
        int fail = (int) attempts.stream().filter(a -> a.verdict() == Verdict.FAIL).count();
        int timeout = (int) attempts.stream().filter(a -> a.verdict() == Verdict.TIMEOUT).count();
        int error = (int) attempts.stream().filter(a -> a.verdict() == Verdict.ERROR).count();
        double totalCost = attempts.stream().mapToDouble(AttemptReport::estimatedCost).sum();
        List<Long> walls =
            attempts.stream().map(AttemptReport::wallClockMs).sorted(Comparator.naturalOrder()).toList();
        double p50Wall = walls.isEmpty() ? 0 : walls.get(walls.size() / 2);
        double avgWall = attempts.stream().mapToLong(AttemptReport::wallClockMs).average().orElse(0);
        List<Long> ttfts = attempts.stream().map(AttemptReport::ttftMs).filter(v -> v != null).toList();
        Double avgTtft =
            ttfts.isEmpty() ? null : ttfts.stream().mapToLong(Long::longValue).average().orElse(0);
        double avgModel = attempts.stream().mapToLong(AttemptReport::modelTimeMs).average().orElse(0);
        double avgTool = attempts.stream().mapToLong(AttemptReport::toolTimeMs).average().orElse(0);
        double avgRounds = attempts.stream().mapToInt(AttemptReport::rounds).average().orElse(0);
        long prompt = attempts.stream().mapToLong(AttemptReport::promptTokens).sum();
        long cached = attempts.stream().mapToLong(AttemptReport::cachedTokens).sum();
        long completion = attempts.stream().mapToLong(AttemptReport::completionTokens).sum();
        double cacheRate = prompt == 0 ? 0.0 : (double) cached / prompt;

        return new SuiteReport(
            batchId,
            Instant.now().toString(),
            model,
            baseUrl,
            currency,
            total,
            pass,
            fail,
            timeout,
            error,
            total == 0 ? 0.0 : (double) pass / total,
            totalCost,
            pass == 0 ? null : totalCost / pass,
            avgWall,
            p50Wall,
            avgTtft,
            avgModel,
            avgTool,
            avgRounds,
            prompt,
            cached,
            completion,
            cacheRate,
            caseSummaries);
    }

    public static void writeAll(
        Path batchDir,
        SuiteReport suite,
        List<AttemptReport> attempts,
        SuiteReport baseline,
        ObjectMapper mapper)
        throws IOException {
        ObjectMapper pretty = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        Files.createDirectories(batchDir);
        for (AttemptReport attempt : attempts) {
            Path caseDir = batchDir.resolve(attempt.caseId());
            Files.createDirectories(caseDir);
            pretty.writeValue(caseDir.resolve("attempt-" + attempt.attempt() + ".json").toFile(), attempt);
        }
        pretty.writeValue(batchDir.resolve("suite.json").toFile(), suite);
        Files.writeString(batchDir.resolve("report.md"), renderMarkdown(suite, baseline));
    }

    public static SuiteReport readSuite(Path path, ObjectMapper mapper) throws IOException {
        Path file = Files.isDirectory(path) ? path.resolve("suite.json") : path;
        return mapper.readValue(file.toFile(), SuiteReport.class);
    }

    public static String renderMarkdown(SuiteReport suite, SuiteReport baseline) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Qgents Agent Eval Report — ").append(suite.batchId()).append("\n\n");
        sb.append("- **Model**: `").append(suite.model()).append("`\n");
        sb.append("- **Base URL**: `").append(suite.baseUrl()).append("`\n");
        sb.append(
            String.format(
                Locale.ROOT,
                "- **Pass Rate**: %.1f%% (%d/%d, fail=%d, timeout=%d, error=%d)%s\n",
                suite.passRate() * 100,
                suite.passed(),
                suite.totalAttempts(),
                suite.failed(),
                suite.timedOut(),
                suite.errored(),
                baseline == null
                    ? ""
                    : String.format(
                        Locale.ROOT, " (Δ %+.1f%%)", (suite.passRate() - baseline.passRate()) * 100)));
        sb.append(
            String.format(
                Locale.ROOT,
                "- **Cost**: %.4f %s | **Cost/Pass**: %s | **Tokens**: in=%d (cached=%d, %.1f%%), out=%d\n",
                suite.totalCost(),
                suite.currency(),
                suite.costPerPass() == null
                    ? "N/A"
                    : String.format(Locale.ROOT, "%.4f %s", suite.costPerPass(), suite.currency()),
                suite.totalPromptTokens(),
                suite.totalCachedTokens(),
                suite.cacheHitRate() * 100,
                suite.totalCompletionTokens()));
        sb.append(
            String.format(
                Locale.ROOT,
                "- **Speed**: avg=%.0fms (p50=%.0fms) | TTFT=%s | model=%.0fms | tool=%.0fms | avgRounds=%.1f\n\n",
                suite.avgWallClockMs(),
                suite.p50WallClockMs(),
                suite.avgTtftMs() == null ? "N/A" : String.format(Locale.ROOT, "%.0fms", suite.avgTtftMs()),
                suite.avgModelTimeMs(),
                suite.avgToolTimeMs(),
                suite.avgRounds()));

        sb.append("| Case | Suite | Pass | Avg Wall | Avg TTFT | Avg Rounds | Cost |\n");
        sb.append("| :--- | :--- | :---: | :---: | :---: | :---: | :---: |\n");
        for (CaseSummary c : suite.cases()) {
            sb.append(
                String.format(
                    Locale.ROOT,
                    "| %s | %s | %d/%d (%.0f%%) | %.0fms | %.0fms | %.1f | %.4f %s |\n",
                    c.caseId(),
                    c.suite(),
                    c.passed(),
                    c.attempts(),
                    c.passRate() * 100,
                    c.avgWallClockMs(),
                    c.avgTtftMs(),
                    c.avgRounds(),
                    c.totalCost(),
                    suite.currency()));
        }
        return sb.toString();
    }
}

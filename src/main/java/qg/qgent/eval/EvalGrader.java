package qg.qgent.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import qg.qgent.harness.entity.AgentRunToolEntity;
import qg.qgent.harness.model.ModelClient;

/**
 * 确定性为主、二元是非题 Judge 兜底的判分器：
 * 支持 trace（工具轨迹）、response（回答事实/红线）、file（记忆/工作区文件）、coding（隐藏测试+diff）、rubric（是非清单）。
 */
public final class EvalGrader {

    private EvalGrader() {}

    public record GradingContext(
        String finalResponse,
        List<AgentRunToolEntity> tools,
        Map<String, String> initialContents,
        Map<String, String> finalContents,
        Path workspaceRepoDir,
        String baseSha,
        Path evalsRoot,
        EvalCase.CodingRepoSpec codingRepo,
        ModelClient judgeModel) {}

    public record GradingResult(
        EvalReport.Verdict verdict, List<EvalReport.CheckResult> checks, List<String> evidence) {}

    public static GradingResult grade(EvalCase evalCase, GradingContext ctx)
        throws IOException, InterruptedException {
        List<EvalReport.CheckResult> results = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        for (EvalCase.CheckSpec check : evalCase.getChecks()) {
            String kind = check.getKind() == null ? "" : check.getKind().trim().toLowerCase();
            EvalReport.CheckResult result =
                switch (kind) {
                    case "trace" -> checkTrace(check, ctx.tools());
                    case "response" -> checkText("response", check, ctx.finalResponse(), null);
                    case "file" -> checkFile(check, ctx);
                    case "coding" -> checkCoding(check, ctx);
                    case "rubric" -> checkRubric(check, ctx);
                    default -> throw new IllegalArgumentException("不支持的检查类型: " + check.getKind());
                };
            results.add(result);
            evidence.add((result.passed() ? "[PASS] " : "[FAIL] ") + result.kind() + ": " + result.detail());
        }
        boolean allPassed = results.stream().allMatch(EvalReport.CheckResult::passed);
        return new GradingResult(
            allPassed ? EvalReport.Verdict.PASS : EvalReport.Verdict.FAIL, results, evidence);
    }

    private static EvalReport.CheckResult checkTrace(
        EvalCase.CheckSpec check, List<AgentRunToolEntity> tools) {
        List<String> called = tools.stream().map(AgentRunToolEntity::getTool).toList();
        for (String required : check.getRequireTools()) {
            if (!called.contains(required)) {
                return new EvalReport.CheckResult("trace", false, "缺少必需工具调用: " + required);
            }
        }
        for (String forbidden : check.getForbidTools()) {
            if (called.contains(forbidden)) {
                return new EvalReport.CheckResult("trace", false, "触发了禁止调用的工具: " + forbidden);
            }
        }
        if (check.getReadTool() != null && check.getBeforeTool() != null) {
            int readOkIndex = -1;
            int beforeIndex = -1;
            for (int i = 0; i < tools.size(); i++) {
                AgentRunToolEntity t = tools.get(i);
                if (readOkIndex < 0
                    && check.getReadTool().equals(t.getTool())
                    && !"failed".equalsIgnoreCase(t.getState())) {
                    readOkIndex = i;
                }
                if (beforeIndex < 0 && check.getBeforeTool().equals(t.getTool())) {
                    beforeIndex = i;
                }
            }
            if (beforeIndex < 0) {
                return new EvalReport.CheckResult(
                    "trace", false, "未调用预期工具: " + check.getBeforeTool());
            }
            if (readOkIndex < 0 || readOkIndex > beforeIndex) {
                return new EvalReport.CheckResult(
                    "trace",
                    false,
                    check.getBeforeTool() + " 之前未成功完成 " + check.getReadTool());
            }
        }
        return new EvalReport.CheckResult("trace", true, "工具轨迹符合预期 (" + called.size() + " 次调用)");
    }

    private static EvalReport.CheckResult checkFile(EvalCase.CheckSpec check, GradingContext ctx)
        throws IOException {
        String target = check.getTarget();
        String actual = ctx.finalContents().get(target);
        String initial = ctx.initialContents().get(target);
        if (actual == null && ctx.workspaceRepoDir() != null && target != null) {
            Path file = ctx.workspaceRepoDir().resolve(target);
            actual = Files.exists(file) ? Files.readString(file) : "";
        }
        actual = actual == null ? "" : actual;
        if (check.isUnchanged()) {
            String expectedInitial = initial == null ? "" : initial;
            if (!actual.equals(expectedInitial)) {
                return new EvalReport.CheckResult("file", false, target + " 预期保持不变，但已被修改");
            }
            return new EvalReport.CheckResult("file", true, target + " 保持未修改");
        }
        return checkText("file:" + target, check, actual, null);
    }

    private static EvalReport.CheckResult checkText(
        String label, EvalCase.CheckSpec check, String text, String ignored) {
        String content = text == null ? "" : text;
        for (String expected : check.getMustContain()) {
            if (!content.contains(expected)) {
                return new EvalReport.CheckResult(label, false, "缺少预期内容: " + expected);
            }
        }
        for (String forbidden : check.getMustNotContain()) {
            if (content.contains(forbidden)) {
                return new EvalReport.CheckResult(label, false, "包含禁止出现的内容: " + forbidden);
            }
        }
        if (check.getRegex() != null && !check.getRegex().isBlank()) {
            if (!Pattern.compile(check.getRegex(), Pattern.DOTALL).matcher(content).find()) {
                return new EvalReport.CheckResult(label, false, "未匹配正则: " + check.getRegex());
            }
        }
        return new EvalReport.CheckResult(label, true, "文本断言全部命中");
    }

    private static EvalReport.CheckResult checkCoding(EvalCase.CheckSpec check, GradingContext ctx)
        throws IOException, InterruptedException {
        if (ctx.workspaceRepoDir() == null || ctx.codingRepo() == null) {
            throw new IllegalStateException("代码检查缺少工作区仓库上下文");
        }
        Path hiddenDir =
            ctx.codingRepo().getHiddenTestDir() == null
                ? null
                : ctx.evalsRoot().resolve(ctx.codingRepo().getHiddenTestDir());
        LocalCodingRepoMaterializer.CodingVerification verification =
            LocalCodingRepoMaterializer.verifyInIsolatedCopy(
                ctx.workspaceRepoDir(),
                hiddenDir,
                ctx.codingRepo().getVerifyCommand(),
                ctx.baseSha());
        if (check.isRequireNonEmptyDiff() && verification.changedFiles().isEmpty()) {
            return new EvalReport.CheckResult("coding", false, "工作树没有任何代码改动");
        }
        if (!check.getAllowedPathPrefixes().isEmpty()) {
            for (String changed : verification.changedFiles()) {
                boolean allowed =
                    check.getAllowedPathPrefixes().stream().anyMatch(changed::startsWith);
                if (!allowed) {
                    return new EvalReport.CheckResult(
                        "coding", false, "改动超出允许范围: " + changed);
                }
            }
        }
        if (!verification.passed()) {
            String summary =
                verification.output().length() > 400
                    ? verification.output().substring(0, 400) + "..."
                    : verification.output();
            return new EvalReport.CheckResult(
                "coding",
                false,
                "隐藏测试失败 (exit=" + verification.exitCode() + "): " + summary.replace('\n', ' '));
        }
        return new EvalReport.CheckResult(
            "coding",
            true,
            "隐藏测试通过 (改动文件: " + String.join(", ", verification.changedFiles()) + ")");
    }

    private static EvalReport.CheckResult checkRubric(EvalCase.CheckSpec check, GradingContext ctx) {
        if (ctx.judgeModel() == null) {
            return new EvalReport.CheckResult("rubric", false, "未配置 Judge 模型（--judge-base-url / --judge-model）");
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("请严格根据以下 Agent 最终回复，逐条回答是非题。每行只输出 `Q序号: YES - 简短理由` 或 `Q序号: NO - 简短理由`。\n\n");
        prompt.append("【Agent 回复】\n").append(ctx.finalResponse()).append("\n\n【检查项】\n");
        List<String> questions = check.getQuestions();
        for (int i = 0; i < questions.size(); i++) {
            prompt.append("Q").append(i + 1).append(": ").append(questions.get(i)).append("\n");
        }
        String judgeOutput =
            ctx.judgeModel()
                .complete("你是严格的评测裁判，只依据给定文本客观判断 YES 或 NO，不得臆测。", prompt.toString());
        List<String> failed = new ArrayList<>();
        String upper = judgeOutput == null ? "" : judgeOutput.toUpperCase();
        for (int i = 0; i < questions.size(); i++) {
            String prefix = "Q" + (i + 1) + ":";
            boolean yes =
                upper
                    .lines()
                    .map(String::trim)
                    .filter(line -> line.startsWith(prefix))
                    .anyMatch(line -> line.contains("YES") && !line.contains("NO"));
            if (!yes) {
                failed.add("Q" + (i + 1));
            }
        }
        if (!failed.isEmpty()) {
            return new EvalReport.CheckResult(
                "rubric", false, "Judge 未通过项 " + failed + " | 输出: " + judgeOutput);
        }
        return new EvalReport.CheckResult("rubric", true, "Judge 全部通过 (" + questions.size() + " 项)");
    }
}

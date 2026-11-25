package qg.qgent.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.harness.entity.AgentRunToolEntity;
import qg.qgent.harness.model.ModelClient;
import qg.qgent.harness.runtime.AgentLoop;

class EvalFrameworkTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path EVALS_DIR = Path.of("evals");

    @Test
    void loadsModelsConfigAndEstimatesCachedCostAccurately() throws Exception {
        EvalModelConfig config = EvalModelConfig.load(EVALS_DIR.resolve("models.yaml"), JSON);

        EvalModelConfig.ModelProfile deepseek = config.profile("deepseek-chat");
        assertThat(deepseek).isNotNull();
        assertThat(deepseek.baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(deepseek.model()).isEqualTo("deepseek-chat");

        // 1_000_000 prompt tokens (600k cached @ 0.07 + 400k uncached @ 0.27) + 500k output @ 1.10
        AgentLoop.Metrics metrics =
            new AgentLoop.Metrics(1_000_000, 500_000, 600_000, 0, 120L, 800L, 200L, 2, 0);
        double cost = config.estimateCost("deepseek-chat", "deepseek-chat", metrics);

        assertThat(cost).isCloseTo(0.4 * 0.27 + 0.6 * 0.07 + 0.5 * 1.10, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void loadsAllStarterMemoryAndCodingCases() throws Exception {
        List<EvalCase> cases = EvalCase.loadAll(EVALS_DIR.resolve("cases"), JSON);

        assertThat(cases)
            .extracting(EvalCase::getId)
            .contains(
                "memory-recall-recent",
                "memory-project-update-approved",
                "memory-ephemeral-no-write",
                "memory-cross-group-isolation",
                "memory-multi-turn-preference",
                "coding-discount-rounding",
                "coding-csv-parser");
    }

    @Test
    void gradesMemoryTraceFileAndRubricChecks() throws Exception {
        EvalCase evalCase =
            EvalCase.loadAll(EVALS_DIR.resolve("cases"), JSON).stream()
                .filter(c -> c.getId().equals("memory-project-update-approved"))
                .findFirst()
                .orElseThrow();

        AgentRunToolEntity read = new AgentRunToolEntity();
        read.setTool("read_project_memory");
        read.setState("ok");
        AgentRunToolEntity edit = new AgentRunToolEntity();
        edit.setTool("edit_project_memory");
        edit.setState("ok");

        String updatedMemory =
            """
            # 项目约定
            ## 构建与测试
            - 构建命令：mvn verify
            ## 代码规范
            - 所有公共接口必须写中文 Javadoc
            - 禁止在业务逻辑里静默吞掉异常
            """;

        EvalGrader.GradingResult pass =
            EvalGrader.grade(
                evalCase,
                new EvalGrader.GradingContext(
                    "已更新构建命令为 mvn verify",
                    List.of(read, edit),
                    Map.of("project_memory", "old"),
                    Map.of("project_memory", updatedMemory),
                    null,
                    null,
                    EVALS_DIR,
                    null,
                    null));
        assertThat(pass.verdict()).isEqualTo(EvalReport.Verdict.PASS);

        // 只回复完成但没改文件、没调工具 -> 必须判 FAIL
        EvalGrader.GradingResult fail =
            EvalGrader.grade(
                evalCase,
                new EvalGrader.GradingContext(
                    "已更新构建命令为 mvn verify",
                    List.of(),
                    Map.of("project_memory", "mvn package"),
                    Map.of("project_memory", "mvn package"),
                    null,
                    null,
                    EVALS_DIR,
                    null,
                    null));
        assertThat(fail.verdict()).isEqualTo(EvalReport.Verdict.FAIL);

        // 二元是非题 Rubric Judge 校验
        EvalCase rubricCase = new EvalCase();
        rubricCase.setId("rubric-test");
        EvalCase.CheckSpec rubric = new EvalCase.CheckSpec();
        rubric.setKind("rubric");
        rubric.setQuestions(List.of("是否明确说明了周四 22:00 发布？", "是否没有编造未出现的事实？"));
        rubricCase.setChecks(List.of(rubric));
        ModelClient judge = mock(ModelClient.class);
        when(judge.complete(anyString(), anyString()))
            .thenReturn("Q1: YES - 提到了周四 22:00\nQ2: YES - 无编造");

        EvalGrader.GradingResult rubricPass =
            EvalGrader.grade(
                rubricCase,
                new EvalGrader.GradingContext(
                    "本周周四 22:00 发布", List.of(), Map.of(), Map.of(), null, null, EVALS_DIR, null, judge));
        assertThat(rubricPass.verdict()).isEqualTo(EvalReport.Verdict.PASS);
    }

    @Test
    void calibratesLightweightCodingRepoBaseFailsAndReferenceFixPasses(@TempDir Path tempDir)
        throws Exception {
        EvalCase discountCase =
            EvalCase.loadAll(EVALS_DIR.resolve("cases"), JSON).stream()
                .filter(c -> c.getId().equals("coding-discount-rounding"))
                .findFirst()
                .orElseThrow();

        Path repoDir = tempDir.resolve("tiny-ledger");
        String baseSha =
            LocalCodingRepoMaterializer.materializeRepo(
                EVALS_DIR.resolve(discountCase.getInitialState().getCodingRepo().getFixtureDir()),
                repoDir);
        assertThat(baseSha).hasSize(40);

        // 1. 原始基线必须不通过隐藏测试
        EvalGrader.GradingResult baseResult =
            EvalGrader.grade(
                discountCase,
                new EvalGrader.GradingContext(
                    "",
                    List.of(),
                    Map.of(),
                    Map.of(),
                    repoDir,
                    baseSha,
                    EVALS_DIR,
                    discountCase.getInitialState().getCodingRepo(),
                    null));
        assertThat(baseResult.verdict()).isEqualTo(EvalReport.Verdict.FAIL);

        // 2. 应用参考修复后，独立验收目录中的隐藏测试必须通过，且原工作树不包含隐藏测试文件
        Path calcFile = repoDir.resolve("src/ledger/PriceCalculator.java");
        String fixed =
            Files.readString(calcFile)
                .replace(
                    "return cents * (100 - discountPercent) / 100;",
                    "return BigDecimal.valueOf(cents)"
                        + ".multiply(BigDecimal.valueOf(100 - discountPercent))"
                        + ".divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)"
                        + ".longValueExact();");
        Files.writeString(calcFile, fixed);

        EvalGrader.GradingResult fixedResult =
            EvalGrader.grade(
                discountCase,
                new EvalGrader.GradingContext(
                    "已修复",
                    List.of(),
                    Map.of(),
                    Map.of(),
                    repoDir,
                    baseSha,
                    EVALS_DIR,
                    discountCase.getInitialState().getCodingRepo(),
                    null));
        assertThat(fixedResult.verdict()).as("evidence=%s", fixedResult.evidence()).isEqualTo(EvalReport.Verdict.PASS);
        assertThat(repoDir.resolve("test/ledger/HiddenDiscountTest.java")).doesNotExist();
    }

    @Test
    void writesSuiteReportAndRendersBaselineComparison(@TempDir Path tempDir) throws Exception {
        EvalReport.AttemptReport attempt =
            new EvalReport.AttemptReport(
                "memory-recall-recent",
                "memory",
                1,
                101L,
                "SUCCEEDED",
                EvalReport.Verdict.PASS,
                "deepseek-chat",
                "https://api.deepseek.com",
                1250L,
                320L,
                900L,
                150L,
                2,
                1,
                0,
                1000L,
                600L,
                200L,
                0L,
                0.0004,
                "USD",
                "周四 22:00 老王负责",
                List.of(new EvalReport.CheckResult("response", true, "ok")),
                List.of("[PASS] response: ok"));

        EvalReport.SuiteReport suite =
            EvalReport.summarize(
                "batch-2", "deepseek-chat", "https://api.deepseek.com", "USD", List.of(attempt));
        EvalReport.SuiteReport baseline =
            new EvalReport.SuiteReport(
                "batch-1",
                "2026-11-24T00:00:00Z",
                "deepseek-chat",
                "https://api.deepseek.com",
                "USD",
                1,
                0,
                1,
                0,
                0,
                0.0,
                0.0005,
                null,
                1500,
                1500,
                400.0,
                1100,
                200,
                3.0,
                1200,
                400,
                250,
                0.33,
                List.of());

        Path batchDir = tempDir.resolve("batch-2");
        EvalReport.writeAll(batchDir, suite, List.of(attempt), baseline, JSON);

        assertThat(batchDir.resolve("suite.json")).exists();
        assertThat(batchDir.resolve("memory-recall-recent/attempt-1.json")).exists();
        String md = Files.readString(batchDir.resolve("report.md"));
        assertThat(md)
            .contains("deepseek-chat")
            .contains("100.0%")
            .contains("Δ +100.0%")
            .contains("TTFT=320ms");
    }
}

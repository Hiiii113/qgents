package qg.qgent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import qg.qgent.QgentsApplication;
import qg.qgent.harness.model.OpenAiModelClient;

/**
 * 独立评测命令行入口（不启动 Tomcat Web 服务、不运行定时任务）。
 * <p>
 * 支持直接传入任意 OpenAI 兼容的 {@code --base-url}、{@code --model}、{@code --api-key}，
 * 或通过 {@code --profile <name>} 从 {@code evals/models.yaml} 加载模型与单价配置。
 */
public final class EvalMain {

    private EvalMain() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> flags = parseArgs(args);
        if (flags.containsKey("help")) {
            printUsage();
            return;
        }
        Path evalsDir = Path.of(flags.getOrDefault("evals-dir", "evals"));
        Path outputRoot = Path.of(flags.getOrDefault("output", "data/evals"));
        String profileName = flags.get("profile");

        ObjectMapper mapper = new ObjectMapper();
        EvalModelConfig modelConfig =
            EvalModelConfig.load(evalsDir.resolve("models.yaml"), mapper);
        EvalModelConfig.ModelProfile profile = modelConfig.profile(profileName);

        Map<String, Object> overrides = new HashMap<>();
        overrides.put("spring.task.scheduling.enabled", "false");
        applyModelOverrides(overrides, profile, flags);

        OpenAiModelClient judge = createJudgeClient(flags, mapper);
        int exitCode = 0;
        try (ConfigurableApplicationContext ctx =
                new SpringApplicationBuilder(QgentsApplication.class)
                    .web(WebApplicationType.NONE)
                    .initializers(
                        applicationContext ->
                            applicationContext
                                .getEnvironment()
                                .getPropertySources()
                                .addFirst(new MapPropertySource("evalCliOverrides", overrides)))
                    .run()) {
            EvalRunner runner = ctx.getBean(EvalRunner.class);
            EvalReport.SuiteReport report =
                runner.run(
                    new EvalRunner.Options(
                        evalsDir,
                        outputRoot,
                        flags.getOrDefault("suite", "all"),
                        flags.get("case"),
                        parseInt(flags.get("repeat")),
                        parseInt(flags.get("timeout")),
                        profileName,
                        flags.containsKey("compare") ? Path.of(flags.get("compare")) : null,
                        judge));
            if (report.errored() > 0) {
                exitCode = 1;
            }
        } finally {
            if (judge != null) judge.destroy();
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static void applyModelOverrides(
        Map<String, Object> overrides,
        EvalModelConfig.ModelProfile profile,
        Map<String, String> flags) {
        if (profile != null) {
            if (profile.baseUrl() != null && !profile.baseUrl().isBlank()) {
                overrides.put("app.ai.base-url", profile.baseUrl());
            }
            if (profile.model() != null && !profile.model().isBlank()) {
                overrides.put("app.ai.model", profile.model());
            }
            String key = profile.resolveApiKey();
            if (key != null) {
                overrides.put("app.ai.api-key", key);
            }
            if (profile.temperature() != null) {
                overrides.put("app.ai.temperature", profile.temperature());
            }
            if (profile.maxTokens() != null) {
                overrides.put("app.ai.max-tokens", profile.maxTokens());
            }
        }
        // 命令行显式传入的 --base-url / --model / --api-key 优先级最高。
        if (flags.containsKey("base-url")) {
            overrides.put("app.ai.base-url", flags.get("base-url"));
        }
        if (flags.containsKey("model")) {
            overrides.put("app.ai.model", flags.get("model"));
        }
        if (flags.containsKey("api-key")) {
            overrides.put("app.ai.api-key", flags.get("api-key"));
        }
    }

    private static OpenAiModelClient createJudgeClient(
        Map<String, String> flags, ObjectMapper mapper) {
        String judgeUrl = flags.get("judge-base-url");
        String judgeModel = flags.get("judge-model");
        if (judgeUrl == null || judgeModel == null) return null;
        String judgeKey =
            flags.getOrDefault("judge-api-key", flags.getOrDefault("api-key", System.getenv("DEEPSEEK_API_KEY")));
        return OpenAiModelClient.forEndpoint(judgeUrl, judgeKey, judgeModel, 0.0, 1024, mapper);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> flags = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) continue;
            String raw = arg.substring(2);
            int eq = raw.indexOf('=');
            if (eq > 0) {
                flags.put(raw.substring(0, eq), raw.substring(eq + 1));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                flags.put(raw, args[++i]);
            } else {
                flags.put(raw, "true");
            }
        }
        return flags;
    }

    private static Integer parseInt(String value) {
        return value == null || value.isBlank() ? null : Integer.parseInt(value);
    }

    private static void printUsage() {
        System.out.println(
            """
            Qgents Agent Eval CLI
              --base-url <url>        OpenAI 兼容接口地址（覆盖配置）
              --model <model-id>      模型 ID（覆盖配置）
              --api-key <key>         API Key（覆盖配置）
              --profile <name>        从 evals/models.yaml 加载模型与单价档案
              --suite <memory|coding|all>  运行指定套件（默认 all）
              --case <case-id>        只运行指定单条用例
              --repeat <n>            覆盖每条用例重复次数
              --timeout <seconds>     覆盖单次运行时限
              --compare <dir>         与历史批次 suite.json 对比输出涨跌
              --evals-dir <dir>       评测配置与用例目录（默认 evals）
              --output <dir>          报告输出根目录（默认 data/evals）
              --judge-base-url <url>  可选：Rubric Judge 接口地址
              --judge-model <id>      可选：Rubric Judge 模型 ID
              --judge-api-key <key>   可选：Rubric Judge API Key
            """);
    }
}

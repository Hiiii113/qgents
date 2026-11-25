package qg.qgent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import qg.qgent.harness.runtime.AgentLoop;

/**
 * 评测模型与计价配置：支持通过 evals/models.yaml 定义可复用的 baseUrl / model / 单价档案，
 * 也支持命令行直接传入任意 OpenAI 兼容的 baseUrl 与 model ID。
 */
public final class EvalModelConfig {

    private final Map<String, ModelProfile> profiles;

    public EvalModelConfig(Map<String, ModelProfile> profiles) {
        this.profiles = profiles == null ? Map.of() : Map.copyOf(profiles);
    }

    public static EvalModelConfig empty() {
        return new EvalModelConfig(Map.of());
    }

    @SuppressWarnings("unchecked")
    public static EvalModelConfig load(Path path, ObjectMapper json) throws IOException {
        if (path == null || !Files.exists(path)) {
            return empty();
        }
        Object raw = new Yaml().load(Files.readString(path));
        if (!(raw instanceof Map<?, ?> map)) {
            return empty();
        }
        Object profilesNode = map.get("profiles");
        if (!(profilesNode instanceof Map<?, ?> rawProfiles)) {
            return empty();
        }
        Map<String, ModelProfile> loaded = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawProfiles.entrySet()) {
            String name = String.valueOf(entry.getKey());
            ModelProfile profile = json.convertValue(entry.getValue(), ModelProfile.class);
            loaded.put(name, profile);
        }
        return new EvalModelConfig(loaded);
    }

    public ModelProfile profile(String name) {
        if (name == null || name.isBlank()) return null;
        return profiles.get(name);
    }

    public ModelProfile findByModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) return null;
        for (ModelProfile profile : profiles.values()) {
            if (modelId.equalsIgnoreCase(profile.model())) {
                return profile;
            }
        }
        return null;
    }

    /**
     * 估算单次运行费用（未命中缓存输入 + 命中缓存输入 + 输出）。未配置单价时返回 0。
     */
    public double estimateCost(String modelId, String profileName, AgentLoop.Metrics metrics) {
        if (metrics == null) return 0.0;
        ModelProfile profile = profile(profileName);
        if (profile == null) profile = findByModelId(modelId);
        if (profile == null) return 0.0;
        long cached = Math.min(metrics.cachedTokens(), metrics.promptTokens());
        long uncached = Math.max(0L, metrics.promptTokens() - cached);
        double inputCost = (uncached / 1_000_000.0) * profile.inputPricePerMillion();
        double cachedCost = (cached / 1_000_000.0) * profile.effectiveCachedPrice();
        double outputCost = (metrics.completionTokens() / 1_000_000.0) * profile.outputPricePerMillion();
        return inputCost + cachedCost + outputCost;
    }

    public String currency(String modelId, String profileName) {
        ModelProfile profile = profile(profileName);
        if (profile == null) profile = findByModelId(modelId);
        return profile == null || profile.currency() == null || profile.currency().isBlank()
            ? "USD"
            : profile.currency();
    }

    public record ModelProfile(
        String baseUrl,
        String model,
        String apiKey,
        String apiKeyEnv,
        Double temperature,
        Long maxTokens,
        double inputPricePerMillion,
        Double cachedInputPricePerMillion,
        double outputPricePerMillion,
        String currency) {

        public String resolveApiKey() {
            if (apiKey != null && !apiKey.isBlank()) return apiKey;
            if (apiKeyEnv != null && !apiKeyEnv.isBlank()) {
                String fromEnv = System.getenv(apiKeyEnv);
                if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
            }
            return null;
        }

        public double effectiveCachedPrice() {
            return cachedInputPricePerMillion != null
                ? cachedInputPricePerMillion
                : inputPricePerMillion;
        }
    }
}

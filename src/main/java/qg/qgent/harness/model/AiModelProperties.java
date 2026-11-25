package qg.qgent.harness.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容模型接入配置：支持通过 application.yaml、环境变量或 CLI 参数覆盖 baseUrl 与 model。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiModelProperties {
    private String apiKey = "";
    private String baseUrl = "https://api.deepseek.com";
    private String model = "deepseek-flash";
    private double temperature = 0.2;
    private long maxTokens = 4096;
}

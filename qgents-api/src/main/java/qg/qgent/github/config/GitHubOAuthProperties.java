package qg.qgent.github.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/** GitHub OAuth 用户授权配置；所有敏感值只能由环境变量或 Secret Manager 注入。 */
@Getter
@Setter
@ConfigurationProperties("github.oauth")
public class GitHubOAuthProperties {
    private boolean enabled;
    private String clientId = "";
    private String clientSecret = "";
    private String authorizeUrl = "https://github.com/login/oauth/authorize";
    private String tokenUrl = "https://github.com/login/oauth/access_token";
    private String userUrl = "https://api.github.com/user";
    private String callbackUrl = "";
    private String stateSecret = "";
    private String tokenEncryptionKey = "";
    /** 默认建仓为私有仓库，因此需要 GitHub 的 repo scope。 */
    private String scopes = "repo";
    private long stateTtlSeconds = 600;

    public boolean configured() {
        return enabled && hasText(clientId) && hasText(clientSecret) && hasText(callbackUrl)
                && hasText(stateSecret) && validEncryptionKey();
    }

    public List<String> scopeList() {
        String raw = scopes == null ? "" : scopes;
        return Arrays.stream(raw.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private boolean validEncryptionKey() {
        if (!hasText(tokenEncryptionKey)) return false;
        try {
            return Base64.getDecoder().decode(tokenEncryptionKey).length == 32;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}

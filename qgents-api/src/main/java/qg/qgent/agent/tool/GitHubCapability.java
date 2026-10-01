package qg.qgent.agent.tool;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.workspace.service.WorkspaceCommands;
import java.util.UUID;
import java.util.function.Supplier;
/** 连接引用属于 Agent；token 由部署环境配置提供，不进入提示词。 */
@Component
@RequiredArgsConstructor
public class GitHubCapability {
    private final Environment environment;
    private final WorkspaceCommands commands;
    public GitHubTools tools(AgentEntity agent, Supplier<UUID> workspace) {
        String ref = agent.getGithubCredentialRef();
        if (ref == null || ref.isBlank()) return null;
        String token = environment.getProperty("app.agent.github."+ref+".token");
        if (token == null || token.isBlank()) throw new IllegalStateException("Agent 的 GitHub 凭证引用未配置："+ref);
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(10));
        requests.setReadTimeout(Duration.ofSeconds(60));
        var http = RestClient.builder().baseUrl("https://api.github.com").requestFactory(requests).build();
        return new GitHubTools(http, token, commands, workspace);
    }
}

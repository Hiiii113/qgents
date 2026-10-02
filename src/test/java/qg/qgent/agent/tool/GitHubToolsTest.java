package qg.qgent.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.workspace.service.WorkspaceCommands;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GitHubToolsTest {
    private final UUID workspace = UUID.randomUUID();
    private final WorkspaceCommands commands = mock(WorkspaceCommands.class);

    @Test
    void appCapabilityUsesImportedRepositoriesAndRenewsTokenForEachRequest() {
        var repositories = mock(qg.qgent.github.service.GitHubRepositoryService.class);
        var actor = UUID.randomUUID();
        var project = UUID.randomUUID();
        var agent = new AgentEntity();
        agent.setTeamId(UUID.randomUUID());
        agent.setGithubEnabled(true);
        var imported = new qg.qgent.github.entity.ProjectRepositoryEntity();
        imported.setFullName("owner/repo");
        when(repositories.listProjectRepositories(actor, project)).thenReturn(List.of(imported));
        var tools = new GitHubCapability(commands, repositories)
                .tools(agent, actor, project, () -> workspace);
        assertThat(tools.repositories()).isEqualTo(List.of(imported));
        verifyNoInteractions(commands);
        when(repositories.repositoryToken(actor, project, agent.getTeamId(), "owner/repo"))
                .thenReturn("fresh-token", "renewed-token");
        tools.cloneRepository("owner/repo", "repo", null);
        tools.push("owner/repo", "repo", "feature");
        verify(repositories, times(2)).repositoryToken(actor, project, agent.getTeamId(), "owner/repo");
        assertThatThrownBy(() -> tools.request("GET", "/user/repos", null)).hasMessageContaining("/repos/owner/repo");
        assertThatThrownBy(() -> tools.git(List.of("fetch"), "repo", null)).hasMessageContaining("owner/repo");
    }

    @Test
    void cloningUsesRemoteDefaultBranchAndTransientAuthentication() {
        when(commands.run(any(), anyString(), anyList(), anyMap())).thenReturn(Map.of("exitCode", 0));
        var tools = tools(RestClient.create(), "test-token-123");
        tools.cloneRepository("owner/repo", "backend", null);
        verify(commands).run(eq(workspace), eq("."), eq(List.of("git", "clone", "--",
                "https://github.com/owner/repo.git", "backend")), argThat(env ->
                env.get("GIT_CONFIG_VALUE_0").startsWith("AUTHORIZATION: basic ")));
    }

    @Test
    void baseBranchIsOptionalAndPushReturnsActualCommandFailure() {
        var failure = Map.<String, Object>of("exitCode", 1, "stderr", "permission denied", "ok", false);
        when(commands.run(any(), anyString(), anyList(), anyMap())).thenReturn(failure);
        var tools = tools(RestClient.create(), "token");
        tools.cloneRepository("owner/repo", "backend", "base");
        verify(commands).run(eq(workspace), eq("."), eq(List.of("git", "clone", "--branch", "base", "--",
                "https://github.com/owner/repo.git", "backend")), anyMap());
        assertThat(tools.push("owner/repo", "backend", "feat/change")).isEqualTo(failure);
        verify(commands).run(eq(workspace), eq("backend"), eq(List.of("git", "push", "--",
                "https://github.com/owner/repo.git", "HEAD:refs/heads/feat/change")), anyMap());
    }

    @Test
    void pullRequestIsCreatedDirectlyOnGithubWithAgentSelectedRepository() {
        var builder = RestClient.builder().baseUrl("https://api.github.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer token"))
                .andExpect(content().json("{\"title\":\"change\",\"head\":\"feat/change\",\"base\":\"main\"}"))
                .andRespond(withSuccess("{\"number\":12,\"html_url\":\"https://github.com/owner/repo/pull/12\"}", MediaType.APPLICATION_JSON));
        var tools = tools(builder.build(), "token");
        assertThat(tools.request("POST", "/repos/owner/repo/pulls",
                Map.of("title", "change", "head", "feat/change", "base", "main"))).isInstanceOf(Map.class);
        server.verify();
        verifyNoInteractions(commands);
    }

    @Test
    void capabilityIsOptionalAndResolvedPerAgent() {
        var repositories = mock(qg.qgent.github.service.GitHubRepositoryService.class);
        var capability = new GitHubCapability(commands, repositories);
        var agent = new AgentEntity();
        var actor = UUID.randomUUID();
        var project = UUID.randomUUID();
        assertThat(capability.tools(agent, actor, project, () -> workspace)).isNull();
        agent.setGithubEnabled(true);
        assertThat(capability.tools(agent, actor, project, () -> workspace)).isNotNull();
        agent.setGithubEnabled(false);
        assertThat(capability.tools(agent, actor, project, () -> workspace)).isNull();
        verifyNoInteractions(repositories, commands);
    }

    @Test
    void githubHttpFailurePropagatesAndAuthenticatedFetchUsesAgentCredentials() {
        var builder = RestClient.builder().baseUrl("https://api.github.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.github.com/repos/owner/repo"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN));
        var tools = tools(builder.build(), "token");
        assertThatThrownBy(() -> tools.request("GET", "/repos/owner/repo", null))
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.class);
        tools.git(List.of("fetch", "origin"), "selected", "owner/repo");
        verify(commands).run(eq(workspace), eq("selected"), eq(List.of("git", "fetch", "origin")), anyMap());
        server.verify();
    }

    private GitHubTools tools(RestClient http, String token) {
        return new GitHubTools(http, repository -> token, commands, () -> workspace, List::of);
    }
}

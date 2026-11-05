package qg.qgent.github.tool;

import qg.qgent.support.TestIds;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.workspace.service.WorkspaceCommands;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GitHubToolsTest {
    private final Long workspace = TestIds.next();
    private final WorkspaceCommands commands = mock(WorkspaceCommands.class);

    @Test
    void appCapabilityUsesImportedRepositoriesAndRenewsTokenForEachRequest() {
        var repositories = mock(qg.qgent.github.service.GitHubRepositoryService.class);
        var actor = TestIds.next();
        var project = TestIds.next();
        var projectEntity = new ProjectEntity();
        projectEntity.setTeamId(TestIds.next());
        projectEntity.setGithubEnabled(true);
        var imported = new qg.qgent.github.entity.ProjectRepositoryEntity();
        imported.setFullName("owner/repo");
        when(repositories.listProjectRepositories(actor, project)).thenReturn(List.of(imported));
        var projects = mock(ProjectMapper.class);
        when(projects.selectById(project)).thenReturn(projectEntity);
        var tools = new GitHubCapability(projects, commands, repositories)
                .tools(actor, project, () -> workspace);
        assertThat(tools.repositories()).isEqualTo(List.of(imported));
        verifyNoInteractions(commands);
        when(repositories.repositoryToken(actor, project, projectEntity.getTeamId(), "owner/repo"))
                .thenReturn("fresh-token", "renewed-token");
        tools.cloneRepository("owner/repo", "repo", null);
        tools.push("owner/repo", "repo", "feature");
        verify(repositories, times(2)).repositoryToken(actor, project, projectEntity.getTeamId(), "owner/repo");
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
    void capabilityDefaultsOnAndCanBeDisabledPerProject() {
        var repositories = mock(qg.qgent.github.service.GitHubRepositoryService.class);
        var projects = mock(ProjectMapper.class);
        var capability = new GitHubCapability(projects, commands, repositories);
        var projectEntity = new ProjectEntity();
        var actor = TestIds.next();
        var project = TestIds.next();
        when(projects.selectById(project)).thenReturn(projectEntity);
        assertThat(capability.tools(actor, project, () -> workspace)).isNotNull();
        projectEntity.setGithubEnabled(true);
        assertThat(capability.tools(actor, project, () -> workspace)).isNotNull();
        projectEntity.setGithubEnabled(false);
        assertThatThrownBy(() -> capability.tools(actor, project, () -> workspace).repositories())
                .hasMessageContaining("当前项目已关闭 GitHub 能力");
        verifyNoInteractions(repositories, commands);
    }

    @Test
    void existingToolsObserveProjectSwitchBeforeCredentialsOrEnvironmentAreUsed() {
        var repositories = mock(qg.qgent.github.service.GitHubRepositoryService.class);
        var projects = mock(ProjectMapper.class);
        var projectEntity = new ProjectEntity();
        var projectId = TestIds.next();
        when(projects.selectById(projectId)).thenReturn(projectEntity);
        java.util.function.Supplier<Long> environment = mock(java.util.function.Supplier.class);
        var tools = new GitHubCapability(projects, commands, repositories)
                .tools(TestIds.next(), projectId, environment);
        projectEntity.setGithubEnabled(false);

        assertThatThrownBy(tools::repositories).hasMessageContaining("当前项目已关闭 GitHub 能力");
        assertThatThrownBy(() -> tools.cloneRepository("owner/repo", "repo", null))
                .hasMessageContaining("当前项目已关闭 GitHub 能力");
        assertThatThrownBy(() -> tools.push("owner/repo", "repo", "feature"))
                .hasMessageContaining("当前项目已关闭 GitHub 能力");
        assertThatThrownBy(() -> tools.git(List.of("fetch", "origin"), "repo", "owner/repo"))
                .hasMessageContaining("当前项目已关闭 GitHub 能力");
        assertThatThrownBy(() -> tools.request("GET", "/repos/owner/repo", null))
                .hasMessageContaining("当前项目已关闭 GitHub 能力");
        verifyNoInteractions(repositories, commands, environment);
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

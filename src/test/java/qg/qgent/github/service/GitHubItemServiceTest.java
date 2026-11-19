package qg.qgent.github.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import qg.qgent.common.web.ApiException;
import qg.qgent.github.entity.ProjectRepositoryEntity;
import qg.qgent.support.TestIds;

/** 项目代码页的 PR/Issue 汇总：分页、仓库筛选、权限与合并标记。 */
class GitHubItemServiceTest {
    private static final String API = "https://api.github.com";
    private final Long actor = TestIds.next();
    private final Long project = TestIds.next();
    private final GitHubRepositoryService repositories = mock(GitHubRepositoryService.class);
    private final RestClient.Builder builder = RestClient.builder().baseUrl(API);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final GitHubItemService service =
            new GitHubItemService(repositories, builder.build());

    @BeforeEach
    void importedRepositories() {
        when(repositories.listProjectRepositories(actor, project))
                .thenReturn(List.of(repository(11, "owner/one"), repository(22, "owner/two")));
        when(repositories.repositoryTokenByProviderId(actor, project, 11L))
                .thenReturn("token-11");
        when(repositories.repositoryTokenByProviderId(actor, project, 22L))
                .thenReturn("token-22");
    }

    @Test
    void aggregatesOpenPullRequestsAcrossRepositoriesAndReadsEveryPage() {
        server.expect(
                        requestTo(
                                API
                                        + "/repos/owner/one/pulls?state=open&per_page=100&page=1"))
                .andExpect(header("Authorization", "Bearer token-11"))
                .andRespond(
                        withSuccess(
                                        "["
                                                + pullRequest(1, "第一页", "2026-01-01T00:00:00Z", false)
                                                + "]",
                                        MediaType.APPLICATION_JSON)
                                .header(
                                        "Link",
                                        "<"
                                                + API
                                                + "/repos/owner/one/pulls?state=open&per_page=100&page=2>; rel=\"next\""));
        server.expect(
                        requestTo(
                                API
                                        + "/repos/owner/one/pulls?state=open&per_page=100&page=2"))
                .andRespond(
                        withSuccess(
                                "["
                                        + pullRequest(2, "第二页", "2026-01-02T00:00:00Z", true)
                                        + "]",
                                MediaType.APPLICATION_JSON));
        server.expect(
                        requestTo(
                                API
                                        + "/repos/owner/two/pulls?state=open&per_page=100&page=1"))
                .andExpect(header("Authorization", "Bearer token-22"))
                .andRespond(
                        withSuccess(
                                "["
                                        + pullRequest(3, "另一个仓库", "2026-01-03T00:00:00Z", false)
                                        + "]",
                                MediaType.APPLICATION_JSON));

        var items = service.pullRequests(actor, project, null, null);

        assertThat(items).hasSize(3);
        assertThat(items)
                .extracting(GitHubItemService.PullRequestItem::repository)
                .containsExactly("owner/two", "owner/one", "owner/one");
        assertThat(items)
                .filteredOn(item -> item.number() == 2)
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.merged()).isTrue();
                            assertThat(item.author()).isEqualTo("dev");
                            assertThat(item.htmlUrl()).endsWith("/pull/2");
                        });
        verify(repositories).listProjectRepositories(actor, project);
        server.verify();
    }

    @Test
    void filtersToOneRepositoryAndExcludesPullRequestsFromIssues() {
        server.expect(
                        requestTo(
                                API
                                        + "/repos/owner/one/issues?state=all&per_page=100&page=1"))
                .andRespond(
                        withSuccess(
                                        "["
                                                + issue(1, "PR 混入", "2026-01-01T00:00:00Z", true)
                                                + ","
                                                + issue(2, "Issue 一", "2026-01-02T00:00:00Z", false)
                                                + "]",
                                        MediaType.APPLICATION_JSON)
                                .header(
                                        "Link",
                                        "<"
                                                + API
                                                + "/repos/owner/one/issues?state=all&per_page=100&page=2>; rel=\"next\""));
        server.expect(
                        requestTo(
                                API
                                        + "/repos/owner/one/issues?state=all&per_page=100&page=2"))
                .andRespond(
                        withSuccess(
                                "["
                                        + issue(3, "Issue 二", "2026-01-03T00:00:00Z", false)
                                        + "]",
                                MediaType.APPLICATION_JSON));

        var items = service.issues(actor, project, 11L, "all");

        assertThat(items)
                .extracting(GitHubItemService.IssueItem::number)
                .containsExactly(3, 2);
        assertThat(items)
                .allSatisfy(item -> assertThat(item.repository()).isEqualTo("owner/one"));
        verify(repositories, never()).repositoryTokenByProviderId(actor, project, 22L);
        server.verify();
    }

    @Test
    void rejectsUnknownStateBeforeReadingAnyRepository() {
        assertThatThrownBy(() -> service.pullRequests(actor, project, null, "merged"))
                .isInstanceOf(ApiException.class)
                .hasMessage("state 必须为 open、closed 或 all");
        verifyNoInteractions(repositories);
        server.verify();
    }

    @Test
    void deniedProjectMembershipStopsBeforeGitHubToken() {
        when(repositories.listProjectRepositories(actor, project))
                .thenThrow(
                        new ApiException(
                                HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见"));

        assertThatThrownBy(() -> service.issues(actor, project, null, "open"))
                .hasMessage("项目不存在或不可见");
        verify(repositories, never()).repositoryTokenByProviderId(any(), any(), any());
        server.verify();
    }

    @Test
    void unparseableNextLinkFailsInsteadOfReturningTruncatedItems() {
        server.expect(
                        requestTo(
                                API
                                        + "/repos/owner/one/pulls?state=open&per_page=100&page=1"))
                .andRespond(
                        withSuccess(
                                        "[" + pullRequest(1, "第一条", "2026-01-01T00:00:00Z", false)
                                                + "]",
                                        MediaType.APPLICATION_JSON)
                                .header(
                                        "Link",
                                        "<" + API + "/repos/owner/one/pulls?state=open>; rel=\"next\""));

        assertThatThrownBy(() -> service.pullRequests(actor, project, 11L, "open"))
                .isInstanceOf(ApiException.class)
                .hasMessage("GitHub 分页链接无法解析");
        server.verify();
    }

    private ProjectRepositoryEntity repository(long providerId, String fullName) {
        var repository = new ProjectRepositoryEntity();
        repository.setProviderRepositoryId(providerId);
        repository.setFullName(fullName);
        return repository;
    }

    private String pullRequest(int number, String title, String updatedAt, boolean merged) {
        return "{\"number\":"
                + number
                + ",\"title\":\""
                + title
                + "\",\"state\":\"open\",\"user\":{\"login\":\"dev\"},\"html_url\":\""
                + API
                + "/repositories/11/pull/"
                + number
                + "\",\"updated_at\":\""
                + updatedAt
                + "\""
                + (merged ? ",\"merged_at\":\"" + updatedAt + "\"" : "")
                + "}";
    }

    private String issue(int number, String title, String updatedAt, boolean pullRequest) {
        return "{\"number\":"
                + number
                + ",\"title\":\""
                + title
                + "\",\"state\":\"open\",\"user\":{\"login\":\"dev\"},\"html_url\":\""
                + API
                + "/repositories/11/issues/"
                + number
                + "\",\"updated_at\":\""
                + updatedAt
                + "\""
                + (pullRequest ? ",\"pull_request\":{\"url\":\"" + API + "/pull/1\"}" : "")
                + "}";
    }
}

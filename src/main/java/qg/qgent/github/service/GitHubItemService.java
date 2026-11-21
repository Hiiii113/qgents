package qg.qgent.github.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import qg.qgent.common.web.ApiException;
import qg.qgent.github.client.GitHubApiException;
import qg.qgent.github.client.GitHubAppClient;
import qg.qgent.github.entity.ProjectRepositoryEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 项目「代码」页的只读 Pull Request 与 Issue 列表：汇总所有已导入仓库，
 * 凭证与项目权限沿用 {@link GitHubRepositoryService}。
 */
@Service
public class GitHubItemService {
    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");
    private static final Pattern PAGE = Pattern.compile("[?&]page=(\\d+)");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PER_PAGE = 100;

    private final GitHubRepositoryService repositories;
    private final RestClient http;

    @Autowired
    public GitHubItemService(GitHubRepositoryService repositories) {
        this(repositories, GitHubAppClient.httpBuilder().build());
    }

    GitHubItemService(GitHubRepositoryService repositories, RestClient http) {
        this.repositories = repositories;
        this.http = http;
    }

    /**
     * repositoryId 是 GitHub 仓库数字 ID；为空时汇总所有已导入仓库。
     */
    public List<PullRequestItem> pullRequests(
        Long actor, Long projectId, Long repositoryId, String state) {
        String itemState = itemState(state);
        List<PullRequestItem> items = new ArrayList<>();
        for (var repository : imported(actor, projectId, repositoryId)) {
            String token = token(actor, projectId, repository);
            for (JsonNode row :
                rows("/repos/" + repository.getFullName() + "/pulls", itemState, token)) {
                items.add(
                    new PullRequestItem(
                        repository.getFullName(),
                        row.path("number").asInt(),
                        text(row, "title"),
                        row.path("state").asText(),
                        row.hasNonNull("merged_at"),
                        text(row, "user", "login"),
                        row.path("html_url").asText(),
                        row.path("updated_at").asText("")));
            }
        }
        items.sort(Comparator.comparing(PullRequestItem::updatedAt).reversed());
        return items;
    }

    public List<IssueItem> issues(Long actor, Long projectId, Long repositoryId, String state) {
        String itemState = itemState(state);
        List<IssueItem> items = new ArrayList<>();
        for (var repository : imported(actor, projectId, repositoryId)) {
            String token = token(actor, projectId, repository);
            for (JsonNode row :
                rows("/repos/" + repository.getFullName() + "/issues", itemState, token)) {
                // GitHub 的 issues 接口会混入 Pull Request，这里按同一契约排除。
                if (row.has("pull_request")) continue;
                items.add(
                    new IssueItem(
                        repository.getFullName(),
                        row.path("number").asInt(),
                        text(row, "title"),
                        row.path("state").asText(),
                        text(row, "user", "login"),
                        row.path("html_url").asText(),
                        row.path("updated_at").asText("")));
            }
        }
        items.sort(Comparator.comparing(IssueItem::updatedAt).reversed());
        return items;
    }

    private List<ProjectRepositoryEntity> imported(Long actor, Long projectId, Long repositoryId) {
        return repositories.listProjectRepositories(actor, projectId).stream()
            .filter(
                repository ->
                    repositoryId == null
                        || repositoryId.equals(repository.getProviderRepositoryId()))
            .toList();
    }

    private String token(Long actor, Long projectId, ProjectRepositoryEntity repository) {
        return repositories.repositoryTokenByProviderId(
            actor, projectId, repository.getProviderRepositoryId());
    }

    /**
     * 逐页读完全部结果；分页链接无法解析时报错，不返回被截断的列表。
     */
    private List<JsonNode> rows(String path, String state, String token) {
        List<JsonNode> rows = new ArrayList<>();
        for (int page = 1; ; ) {
            ResponseEntity<String> response =
                get(
                    path + "?state=" + state + "&per_page=" + PER_PAGE + "&page=" + page,
                    token);
            JsonNode body = read(response.getBody());
            if (!body.isArray()) throw failed("GitHub 列表响应不是数组");
            for (JsonNode row : body) rows.add(row);
            Integer next = nextPage(response.getHeaders().getFirst("Link"));
            if (next == null) return rows;
            if (next <= page) throw failed("GitHub 分页链接没有前进");
            page = next;
        }
    }

    private ResponseEntity<String> get(String path, String token) {
        try {
            return http.get()
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .retrieve()
                .toEntity(String.class);
        } catch (RestClientException e) {
            throw GitHubApiException.from(e);
        }
    }

    private Integer nextPage(String link) {
        if (link == null) return null;
        Matcher next = NEXT_LINK.matcher(link);
        if (!next.find()) return null;
        Matcher page = PAGE.matcher(next.group(1));
        if (!page.find()) throw failed("GitHub 分页链接无法解析");
        return Integer.valueOf(page.group(1));
    }

    private JsonNode read(String body) {
        try {
            return JSON.readTree(body);
        } catch (JsonProcessingException e) {
            throw failed("GitHub 响应不是合法 JSON");
        }
    }

    private String text(JsonNode row, String field) {
        return row.hasNonNull(field) ? row.get(field).asText() : null;
    }

    private String text(JsonNode row, String parent, String field) {
        return row.path(parent).hasNonNull(field) ? row.path(parent).get(field).asText() : null;
    }

    private String itemState(String state) {
        if (state == null || state.isBlank()) return "open";
        String value = state.trim().toLowerCase(Locale.ROOT);
        if (!List.of("open", "closed", "all").contains(value))
            throw invalid("state 必须为 open、closed 或 all");
        return value;
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GITHUB_ARGUMENT", message);
    }

    private ApiException failed(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_ITEM_LIST_FAILED", message);
    }

    public record PullRequestItem(
        String repository,
        int number,
        String title,
        String state,
        boolean merged,
        String author,
        String htmlUrl,
        String updatedAt) {
    }

    public record IssueItem(
        String repository,
        int number,
        String title,
        String state,
        String author,
        String htmlUrl,
        String updatedAt) {
    }
}

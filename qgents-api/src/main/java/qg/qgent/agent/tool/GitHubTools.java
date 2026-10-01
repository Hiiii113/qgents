package qg.qgent.agent.tool;

import org.springframework.ai.tool.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.http.HttpMethod;
import qg.qgent.workspace.service.WorkspaceCommands;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.Function;

/** Agent 的可选 GitHub 工具；App 模式按项目导入仓库获取短期凭证，不保存 PR 镜像。 */
public class GitHubTools {
    private final RestClient http;
    private final Function<String, String> credential;
    private final Supplier<?> repositories;
    private final WorkspaceCommands commands;
    private final Supplier<UUID> workspace;
    public GitHubTools(RestClient http, String token, WorkspaceCommands commands, Supplier<UUID> workspace) {
        this(http, repository -> token, commands, workspace, null);
    }
    public GitHubTools(RestClient http, Function<String, String> credential, WorkspaceCommands commands,
                       Supplier<UUID> workspace, Supplier<?> repositories) {
        this.http = http;
        this.credential = credential;
        this.repositories = repositories;
        this.commands = commands;
        this.workspace = workspace;
    }
    @Tool(name = "github_request", description = "调用当前 Agent 的 GitHub 能力。path 是相对 API 路径，例如 GET /repos/owner/repo、POST /repos/owner/repo/pulls。App 模式只能操作当前项目已导入仓库，仓库清单通过 github_repositories 查询。写请求按用户任务目标决定调用。创建 PR body 需 title/head/base，可选 body。支持分页 query。")
    public Object request(@ToolParam(description = "GET/POST/PATCH/PUT/DELETE") String method,
                          @ToolParam(description = "GitHub API 相对路径，可含 query") String path,
                          @ToolParam(description = "JSON 请求正文", required = false) Map<String, Object> body) {
        if (path == null || !path.matches("/(?:repos/|user(?:/repos|[?]|$)|orgs/|search/).*" ) || path.contains("..") || path.contains("://") || path.contains("#"))
            throw new IllegalArgumentException("不支持的 GitHub API 路径");
        String repository = repositoryFromPath(path);
        if (repositories != null && repository == null)
            throw new IllegalArgumentException("App 模式请使用 /repos/owner/repo 路径；仓库清单通过 github_repositories 查询");
        String token = token(repository);
        var request = http.method(HttpMethod.valueOf(method.toUpperCase(Locale.ROOT))).uri(path)
                .header("Authorization","Bearer "+token).header("Accept","application/vnd.github+json")
                .header("X-GitHub-Api-Version","2026-03-10");
        if (body != null) request.body(body);
        return request.retrieve().body(Object.class);
    }
    @Tool(name = "github_clone", description = "将 Agent 选择的 GitHub 仓库 clone 到当前选择的工作区。App 模式需先导入当前项目。base 省略时使用远端默认分支；不自动创建工作分支，后续通过 run_command 使用 git switch -c。")
    public Object cloneRepository(@ToolParam(description = "owner/repo") String repository,
                                  @ToolParam(description = "工作区内新目录") String directory,
                                  @ToolParam(description = "基线分支，省略使用远端默认分支", required = false) String base) {
        requireRepository(repository);
        String token = token(repository);
        if (directory == null || directory.isBlank() || directory.startsWith("-") || directory.contains("..") || (directory.startsWith("/") || directory.startsWith("\\")) || directory.contains(":"))
            throw new IllegalArgumentException("仓库目录必须在工作区内");
        List<String> command = new ArrayList<>(List.of("git","clone"));
        if (base != null && !base.isBlank()) command.addAll(List.of("--branch", base));
        command.addAll(List.of("--","https://github.com/"+repository+".git", directory));
        return commands.run(workspace.get(),".", command, gitEnvironment(token));
    }
    @Tool(name = "github_push", description = "将当前工作区仓库的 HEAD 推送到指定 GitHub 分支，不自动 commit，不创建 PR；按用户目标决定使用。")
    public Object push(@ToolParam(description = "owner/repo") String repository,
                       @ToolParam(description = "工作区内仓库目录") String directory,
                       @ToolParam(description = "远端目标分支") String branch) {
        requireRepository(repository);
        String token = token(repository);
        if (branch == null || !branch.matches("[A-Za-z0-9][A-Za-z0-9._/-]*") || branch.contains(".."))
            throw new IllegalArgumentException("分支名不合法");
        return commands.run(workspace.get(), directory, List.of("git","push","--","https://github.com/"+repository+".git","HEAD:refs/heads/"+branch), gitEnvironment(token));
    }
    private void requireRepository(String repository) {
        if (repository == null || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || repository.contains(".."))
            throw new IllegalArgumentException("仓库名称应为 owner/repo");
    }

    @Tool(name = "github_git", description = "在工作区内执行带当前 Agent GitHub 认证的 Git 参数数组，例如 fetch、pull 或 ls-remote；App 模式需指定已导入仓库。远端操作按用户目标决定。command 不包含开头的 git。")
    public Object git(@ToolParam(description = "例如 [fetch,origin]") List<String> command,
                      @ToolParam(description = "工作区内目录", required = false) String directory,
                      @ToolParam(description = "目标 owner/repo；App 模式必填且必须已导入当前项目", required = false) String repository) {
        if (repositories != null) requireRepository(repository);
        String token = token(repository);
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("Git 命令不能为空");
        List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.addAll(command);
        return commands.run(workspace.get(), directory, argv, gitEnvironment(token));
    }
    @Tool(name = "github_repositories", description = "列出当前项目已导入的 GitHub 仓库及默认分支；不创建沙箱")
    public Object repositories() {
        return repositories == null ? request("GET", "/user/repos", null) : repositories.get();
    }
    private String token(String repository) {
        String token = credential.apply(repository);
        if (token == null || token.isBlank()) throw new IllegalStateException("当前 Agent 的 GitHub 凭证未配置");
        return token;
    }

    private String repositoryFromPath(String path) {
        var matcher = java.util.regex.Pattern.compile("^/repos/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)(?:/|\\?|$)").matcher(path);
        return matcher.find() ? matcher.group(1) : null;
    }
    private Map<String, String> gitEnvironment(String token) {
        String auth = Base64.getEncoder().encodeToString(("x-access-token:"+token).getBytes(StandardCharsets.UTF_8));
        return Map.of("GIT_CONFIG_COUNT","1","GIT_CONFIG_KEY_0","http.https://github.com/.extraheader",
                "GIT_CONFIG_VALUE_0","AUTHORIZATION: basic "+auth,"GIT_TERMINAL_PROMPT","0");
    }
}

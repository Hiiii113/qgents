package qg.qgent.github.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;
import qg.qgent.auth.service.TokenService;
import qg.qgent.common.web.ApiException;
import qg.qgent.github.client.GitHubAppClient;
import qg.qgent.github.config.GitHubAppProperties;
import qg.qgent.github.entity.*;
import qg.qgent.github.mapper.*;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** 团队连接 App、项目导入仓库，以及 Agent 操作所需的安装凭证。 */
@Service
@RequiredArgsConstructor
public class GitHubRepositoryService {
    private final GitHubInstallationMapper installations;
    private final GitHubInstallationStateMapper states;
    private final ProjectRepositoryMapper repositories;
    private final ProjectMapper projects;
    private final ProjectAccessService access;
    private final GitHubAppClient app;
    private final GitHubAppProperties properties;
    private final TokenService tokens;

    @Transactional
    public InstallationUrl createInstallationUrl(Long actor, Long teamId, String client) {
        requireOwner(actor, teamId);
        app.requireConfigured();
        if (!List.of("WEB", "MOBILE").contains(client)) throw invalid("client 必须为 WEB 或 MOBILE");
        String raw = tokens.opaque();
        var state = new GitHubInstallationStateEntity();
        state.setActorId(actor);
        state.setTeamId(teamId);
        state.setStateHash(tokens.hash(raw));
        state.setClient(client);
        state.setExpiresAt(now().plusMinutes(10));
        states.insert(state);
        String url = UriComponentsBuilder.fromUriString("https://github.com/apps/" + properties.getSlug() + "/installations/new")
                .queryParam("state", raw).queryParam("redirect_uri", properties.getCallbackUrl()).build().encode().toUriString();
        return new InstallationUrl(url, state.getExpiresAt());
    }

    @Transactional
    public CallbackResult callback(long providerId, String raw, String setupAction) {
        if (providerId <= 0) throw invalid("安装 ID 不合法");
        // GitHub 配置页更新回调不携带初始 state；只刷新已有连接，不建立新的团队归属。
        if ((raw == null || raw.isBlank()) && "update".equals(setupAction)) {
            var installation = byProviderId(providerId);
            if (installation == null) throw invalid("安装尚未连接团队，请从团队安装入口发起");
            refresh(installation);
            return new CallbackResult(installation.getTeamId(), "WEB");
        }
        if (raw == null || raw.isBlank() || raw.length() > 512) throw invalid("安装回调凭据无效");
        var state = states.selectOne(Wrappers.<GitHubInstallationStateEntity>lambdaQuery()
                .eq(GitHubInstallationStateEntity::getStateHash, tokens.hash(raw)).last("FOR UPDATE"));
        if (state == null || state.getUsedAt() != null || !state.getExpiresAt().isAfter(now()))
            throw invalid("安装回调已使用或过期，请重新发起安装");
        requireOwner(state.getActorId(), state.getTeamId());
        var installation = byProviderId(providerId);
        if (installation != null && !state.getTeamId().equals(installation.getTeamId()))
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_INSTALLATION_TEAM_CONFLICT", "此安装已连接其他团队");
        if (installation == null) {
            installation = new GitHubInstallationEntity();
            installation.setTeamId(state.getTeamId());
            installation.setProviderInstallationId(providerId);
            apply(installation, app.installation(providerId));
            try { installations.insert(installation); }
            catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "GITHUB_INSTALLATION_TEAM_CONFLICT", "此安装已连接团队");
            }
        } else refresh(installation);
        state.setUsedAt(now());
        states.updateById(state);
        return new CallbackResult(state.getTeamId(), state.getClient());
    }

    public List<GitHubInstallationEntity> listInstallations(Long actor, Long teamId) {
        access.requireTeamMember(teamId, actor);
        return installations.selectList(Wrappers.<GitHubInstallationEntity>lambdaQuery()
                .eq(GitHubInstallationEntity::getTeamId, teamId));
    }

    public List<AvailableRepository> listTeamRepositories(Long actor, Long teamId) {
        return listInstallations(actor, teamId).stream().filter(i -> !Boolean.TRUE.equals(i.getSuspended()))
                .flatMap(i -> app.repositories(i.getProviderInstallationId()).stream()
                        .map(r -> new AvailableRepository(i.getId(), r.repositoryId(), r.fullName(), r.defaultBranch(),
                                r.htmlUrl(), r.privateRepository()))).toList();
    }

    @Transactional
    public GitHubInstallationEntity sync(Long actor, Long teamId, Long installationId) {
        requireOwner(actor, teamId);
        var installation = requireInstallation(teamId, installationId);
        refresh(installation);
        if (!Boolean.TRUE.equals(installation.getSuspended())) refreshImports(installation);
        return installation;
    }

    /** 解除本系统的团队连接并移除对应导入记录，不卸载 GitHub 账号上的 App。 */
    @Transactional
    public void removeInstallation(Long actor, Long teamId, Long installationId) {
        requireOwner(actor, teamId);
        requireInstallation(teamId, installationId);
        installations.deleteById(installationId);
    }

    public List<ProjectRepositoryEntity> listProjectRepositories(Long actor, Long projectId) {
        access.requireProjectMember(projectId, actor);
        return repositories.selectList(Wrappers.<ProjectRepositoryEntity>lambdaQuery()
                .eq(ProjectRepositoryEntity::getProjectId, projectId));
    }

    @Transactional
    public ProjectRepositoryEntity importRepository(Long actor, Long projectId, Long installationId, long repositoryId) {
        access.requireProjectAdmin(projectId, actor);
        var installation = requireInstallation(projects.selectById(projectId).getTeamId(), installationId);
        if (Boolean.TRUE.equals(installation.getSuspended())) throw invalid("GitHub App 安装已暂停");
        var remote = app.repositories(installation.getProviderInstallationId()).stream()
                .filter(r -> r.repositoryId() == repositoryId).findFirst()
                .orElseThrow(() -> invalid("仓库不在此安装的授权范围内"));
        var existing = repositories.selectOne(Wrappers.<ProjectRepositoryEntity>lambdaQuery()
                .eq(ProjectRepositoryEntity::getProjectId, projectId)
                .eq(ProjectRepositoryEntity::getProviderRepositoryId, repositoryId));
        if (existing != null) return existing;
        var repository = new ProjectRepositoryEntity();
        repository.setProjectId(projectId);
        repository.setInstallationId(installationId);
        apply(repository, remote);
        try { repositories.insert(repository); }
        catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "REPOSITORY_ALREADY_IMPORTED", "仓库已导入，请刷新列表");
        }
        return repository;
    }

    @Transactional
    public void removeRepository(Long actor, Long projectId, Long repositoryId) {
        access.requireProjectAdmin(projectId, actor);
        repositories.delete(Wrappers.<ProjectRepositoryEntity>lambdaQuery()
                .eq(ProjectRepositoryEntity::getProjectId, projectId).eq(ProjectRepositoryEntity::getId, repositoryId));
    }

    /** Agent 每次操作都检查当前项目访问权和导入关系，再取得仅授权目标仓库的凭证。 */
    public String repositoryToken(Long actor, Long projectId, Long teamId, String fullName) {
        access.requireProjectMember(projectId, actor);
        if (!teamId.equals(projects.selectById(projectId).getTeamId())) throw invalid("Agent 不属于项目团队");
        var repository = repositories.selectOne(Wrappers.<ProjectRepositoryEntity>lambdaQuery()
                .eq(ProjectRepositoryEntity::getProjectId, projectId).eq(ProjectRepositoryEntity::getFullName, fullName));
        if (repository == null) throw invalid("请先将目标仓库导入当前项目");
        var installation = requireInstallation(teamId, repository.getInstallationId());
        if (Boolean.TRUE.equals(installation.getSuspended())) throw invalid("GitHub App 安装已暂停");
        return app.installationToken(installation.getProviderInstallationId(), repository.getProviderRepositoryId());
    }

    /** 根据 Webhook 同步 App 安装状态和仓库授权。 */
    @Transactional
    public void installationChanged(long providerId, boolean deleted) {
        var installation = byProviderId(providerId);
        if (installation == null) return;
        if (deleted) installations.deleteById(installation.getId());
        else {
            refresh(installation);
            if (!Boolean.TRUE.equals(installation.getSuspended())) refreshImports(installation);
        }
    }

    private void refreshImports(GitHubInstallationEntity installation) {
        var remote = app.repositories(installation.getProviderInstallationId());
        for (var imported : repositories.selectList(Wrappers.<ProjectRepositoryEntity>lambdaQuery()
                .eq(ProjectRepositoryEntity::getInstallationId, installation.getId()))) {
            var match = remote.stream().filter(r -> r.repositoryId() == imported.getProviderRepositoryId()).findFirst();
            if (match.isEmpty()) repositories.deleteById(imported.getId());
            else { apply(imported, match.get()); repositories.updateById(imported); }
        }
    }

    private void refresh(GitHubInstallationEntity installation) {
        apply(installation, app.installation(installation.getProviderInstallationId()));
        installations.updateById(installation);
    }

    private void apply(GitHubInstallationEntity target, GitHubAppClient.Installation remote) {
        target.setAccountLogin(remote.accountLogin());
        target.setAccountType(remote.accountType());
        target.setSuspended(remote.suspended());
    }

    private void apply(ProjectRepositoryEntity target, GitHubAppClient.Repository remote) {
        target.setProviderRepositoryId(remote.repositoryId());
        target.setFullName(remote.fullName());
        target.setDefaultBranch(remote.defaultBranch());
        target.setHtmlUrl(remote.htmlUrl());
        target.setPrivateRepository(remote.privateRepository());
    }

    private GitHubInstallationEntity byProviderId(long id) {
        return installations.selectOne(Wrappers.<GitHubInstallationEntity>lambdaQuery()
                .eq(GitHubInstallationEntity::getProviderInstallationId, id));
    }

    private GitHubInstallationEntity requireInstallation(Long teamId, Long id) {
        var installation = installations.selectById(id);
        if (installation == null || !teamId.equals(installation.getTeamId()))
            throw new ApiException(HttpStatus.NOT_FOUND, "GITHUB_INSTALLATION_NOT_FOUND", "GitHub App 安装不存在或不属于团队");
        return installation;
    }

    private void requireOwner(Long actor, Long teamId) {
        access.requireTeamMember(teamId, actor);
        if (!access.isCanonicalTeamOwner(teamId, actor))
            throw new ApiException(HttpStatus.FORBIDDEN, "TEAM_OWNER_REQUIRED", "需要团队所有者权限");
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GITHUB_ARGUMENT", message);
    }

    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    public record InstallationUrl(String installationUrl, LocalDateTime expiresAt) {}
    public record CallbackResult(Long teamId, String client) {}
    public record AvailableRepository(Long installationId, long repositoryId, String fullName,
                                      String defaultBranch, String htmlUrl, boolean privateRepository) {}
}

package qg.qgent.github.service;

import qg.qgent.support.TestIds;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qg.qgent.auth.service.TokenService;
import qg.qgent.github.client.GitHubAppClient;
import qg.qgent.github.config.GitHubAppProperties;
import qg.qgent.github.entity.*;
import qg.qgent.github.mapper.*;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GitHubRepositoryServiceTest {
    private final Long actor = TestIds.next();
    private final Long team = TestIds.next();
    private final Long project = TestIds.next();
    private final GitHubInstallationMapper installations = mock(GitHubInstallationMapper.class);
    private final GitHubInstallationStateMapper states = mock(GitHubInstallationStateMapper.class);
    private final ProjectRepositoryMapper repositories = mock(ProjectRepositoryMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final GitHubAppClient app = mock(GitHubAppClient.class);
    private final GitHubAppProperties properties = new GitHubAppProperties();
    private final TokenService tokens = new TokenService("01234567890123456789012345678901", 15, 30, 30);
    private final GitHubRepositoryService service = new GitHubRepositoryService(installations, states,
            repositories, projects, access, app, properties, tokens);

    @BeforeEach
    void setup() {
        for (Class<?> type : List.of(GitHubInstallationEntity.class, GitHubInstallationStateEntity.class,
                ProjectRepositoryEntity.class)) {
            if (TableInfoHelper.getTableInfo(type) == null)
                TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "github"), type);
        }
        when(access.isCanonicalTeamOwner(team, actor)).thenReturn(true);
        var entity = new ProjectEntity();
        entity.setId(project);
        entity.setTeamId(team);
        when(projects.selectById(project)).thenReturn(entity);
        properties.setSlug("qgents");
        properties.setCallbackUrl("https://backend.example/api/v1/integrations/github/callback");
    }

    @Test
    void installationStartsWithExpiringOpaqueStateAndRequiresOwner() {
        var result = service.createInstallationUrl(actor, team, "MOBILE");
        assertThat(result.installationUrl()).startsWith("https://github.com/apps/qgents/installations/new?")
                .contains("state=", "redirect_uri=");
        var state = org.mockito.ArgumentCaptor.forClass(GitHubInstallationStateEntity.class);
        verify(states).insert(state.capture());
        assertThat(state.getValue().getStateHash()).hasSize(32);
        assertThat(state.getValue().getActorId()).isEqualTo(actor);
        assertThat(state.getValue().getClient()).isEqualTo("MOBILE");
        assertThat(result.expiresAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC));
        when(access.isCanonicalTeamOwner(team, actor)).thenReturn(false);
        assertThatThrownBy(() -> service.createInstallationUrl(actor, team, "WEB")).hasMessage("需要团队所有者权限");
    }

    @Test
    void callbackConsumesStateAndRepeatedCallbackDoesNotRelink() {
        var state = state();
        when(states.selectOne(any())).thenReturn(state);
        when(app.installation(12L)).thenReturn(new GitHubAppClient.Installation(12L, "owner", "Organization", false));
        var result = service.callback(12L, "state", null);
        assertThat(result.teamId()).isEqualTo(team);
        assertThat(result.client()).isEqualTo("MOBILE");
        assertThat(state.getUsedAt()).isNotNull();
        verify(installations).insert(any(GitHubInstallationEntity.class));
        assertThatThrownBy(() -> service.callback(12L, "state", null)).hasMessageContaining("已使用或过期");
        verify(app).installation(12L);
    }

    @Test
    void expiredStateAndCrossTeamInstallationAreRejected() {
        var state = state();
        state.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(states.selectOne(any())).thenReturn(state);
        assertThatThrownBy(() -> service.callback(12L, "state", null)).hasMessageContaining("过期");
        verifyNoInteractions(app);
        state.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));
        var connected = installation();
        connected.setTeamId(TestIds.next());
        when(installations.selectOne(any())).thenReturn(connected);
        assertThatThrownBy(() -> service.callback(12L, "state", null)).hasMessage("此安装已连接其他团队");
        verifyNoInteractions(app);
    }

    @Test
    void importingRepositoryStoresMetadataAndRepeatedImportReturnsSameRecord() {
        var installation = installation();
        when(installations.selectById(installation.getId())).thenReturn(installation);
        when(app.repositories(12L)).thenReturn(List.of(remote()));
        var imported = service.importRepository(actor, project, installation.getId(), 34L);
        assertThat(imported.getFullName()).isEqualTo("owner/repo");
        assertThat(imported.getDefaultBranch()).isEqualTo("main");
        assertThat(imported.getProjectId()).isEqualTo(project);
        when(repositories.selectOne(any())).thenReturn(imported);
        assertThat(service.importRepository(actor, project, installation.getId(), 34L)).isSameAs(imported);
        verify(repositories).insert(any(ProjectRepositoryEntity.class));
        verify(access, times(2)).requireProjectAdmin(project, actor);
    }

    @Test
    void importingRejectsCrossTeamAndUnauthorisedRepositories() {
        var installation = installation();
        installation.setTeamId(TestIds.next());
        when(installations.selectById(installation.getId())).thenReturn(installation);
        assertThatThrownBy(() -> service.importRepository(actor, project, installation.getId(), 34L))
                .hasMessageContaining("不属于团队");
        verifyNoInteractions(app);
        installation.setTeamId(team);
        when(app.repositories(12L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.importRepository(actor, project, installation.getId(), 34L))
                .hasMessageContaining("授权范围");
        verify(repositories, never()).insert(any(ProjectRepositoryEntity.class));
    }

    @Test
    void agentGetsFreshRepositoryScopedTokensAndCannotUseUnimportedRepository() {
        var installation = installation();
        var imported = new ProjectRepositoryEntity();
        imported.setInstallationId(installation.getId());
        imported.setProviderRepositoryId(34L);
        when(repositories.selectOne(any())).thenReturn(imported);
        when(installations.selectById(installation.getId())).thenReturn(installation);
        when(app.installationToken(12L, 34L)).thenReturn("first", "renewed");
        assertThat(service.repositoryToken(actor, project, team, "owner/repo")).isEqualTo("first");
        assertThat(service.repositoryToken(actor, project, team, "owner/repo")).isEqualTo("renewed");
        when(repositories.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.repositoryToken(actor, project, team, "owner/other"))
                .hasMessageContaining("先将目标仓库导入");
        verify(app, times(2)).installationToken(12L, 34L);
    }

    @Test
    void syncingUpdatesRenamedRepositoryAndRemovesRevokedImport() {
        var installation = installation();
        when(installations.selectById(installation.getId())).thenReturn(installation);
        when(app.installation(12L)).thenReturn(new GitHubAppClient.Installation(12L, "owner", "Organization", false));
        when(app.repositories(12L)).thenReturn(List.of(remote()));
        var keep = new ProjectRepositoryEntity();
        keep.setId(TestIds.next());
        keep.setProviderRepositoryId(34L);
        keep.setFullName("owner/old-name");
        var revoked = new ProjectRepositoryEntity();
        revoked.setId(TestIds.next());
        revoked.setProviderRepositoryId(35L);
        when(repositories.selectList(any())).thenReturn(List.of(keep, revoked));
        service.sync(actor, team, installation.getId());
        assertThat(keep.getFullName()).isEqualTo("owner/repo");
        verify(repositories).updateById(keep);
        verify(repositories).deleteById(revoked.getId());
    }

    private GitHubInstallationEntity installation() {
        var result = new GitHubInstallationEntity();
        result.setId(TestIds.next());
        result.setTeamId(team);
        result.setProviderInstallationId(12L);
        result.setSuspended(false);
        return result;
    }

    private GitHubInstallationStateEntity state() {
        var state = new GitHubInstallationStateEntity();
        state.setId(TestIds.next());
        state.setActorId(actor);
        state.setTeamId(team);
        state.setClient("MOBILE");
        state.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));
        return state;
    }

    private GitHubAppClient.Repository remote() {
        return new GitHubAppClient.Repository(34L, "owner/repo", "main", "https://github.com/owner/repo", true);
    }
}

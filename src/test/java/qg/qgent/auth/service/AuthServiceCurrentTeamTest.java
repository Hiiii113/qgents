package qg.qgent.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import qg.qgent.auth.mail.PasswordResetMailer;
import qg.qgent.auth.mail.VerificationCodeMailer;
import qg.qgent.auth.mapper.EmailVerificationCodeMapper;
import qg.qgent.auth.mapper.PasswordResetTokenMapper;
import qg.qgent.auth.mapper.RefreshTokenMapper;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.team.entity.TeamEntity;
import qg.qgent.team.entity.TeamMemberEntity;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.team.mapper.TeamMemberMapper;
import qg.qgent.user.entity.UserEntity;
import qg.qgent.user.mapper.UserMapper;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceCurrentTeamTest {
    private final UserMapper users = mock(UserMapper.class);
    private final TeamMapper teams = mock(TeamMapper.class);
    private final TeamMemberMapper members = mock(TeamMemberMapper.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID firstId = UUID.randomUUID();
    private final UUID lastId = UUID.randomUUID();
    private final UserEntity user = new UserEntity();
    private AuthService service;

    @BeforeEach
    void setUp() {
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        service = new AuthService(users, mock(RefreshTokenMapper.class), mock(PasswordResetTokenMapper.class),
                mock(EmailVerificationCodeMapper.class), teams, members, mock(ProjectMapper.class),
                mock(RsaPasswordDecryptor.class), passwords, mock(TokenService.class),
                mock(PasswordResetMailer.class), mock(VerificationCodeMailer.class), mock(RateLimiter.class));
        user.setId(userId);
        when(users.selectById(userId)).thenReturn(user);
    }

    @Test
    void restoresLastAccessibleTeamAndIncludesAvatar() {
        available(firstId, lastId);
        user.setLastTeamId(lastId);
        var response = service.me(userId);
        assertEquals(lastId.toString(), response.getCurrentTeamId());
        assertEquals("https://example.test/avatar.png", response.getTeams().getFirst().getAvatarUrl());
        verify(users, never()).updateLastTeam(any(), any());
    }

    @Test
    void fallsBackAndPersistsWhenLastTeamIsUnavailable() {
        available(firstId);
        user.setLastTeamId(lastId);
        assertEquals(firstId.toString(), service.me(userId).getCurrentTeamId());
        verify(users).updateLastTeam(userId, firstId);
    }

    @Test
    void clearsSelectionWhenNoTeamsRemain() {
        user.setLastTeamId(lastId);
        when(members.selectByUserId(userId)).thenReturn(List.of());
        assertNull(service.me(userId).getCurrentTeamId());
        verify(users).updateLastTeam(userId, null);
    }

    @Test
    void savesSelectedTeamForMembers() {
        TeamEntity team = team(firstId);
        when(teams.selectById(firstId)).thenReturn(team);
        when(members.selectByTeamAndUser(firstId, userId)).thenReturn(new TeamMemberEntity());
        service.selectTeam(userId, firstId);
        verify(users).updateLastTeam(userId, firstId);
    }

    @Test
    void rejectsNonMemberAndDisbandedTeamWithoutSaving() {
        when(teams.selectById(firstId)).thenReturn(team(firstId));
        assertThrows(ApiException.class, () -> service.selectTeam(userId, firstId));
        TeamEntity inactive = team(lastId);
        inactive.setStatus("DISBANDED");
        when(teams.selectById(lastId)).thenReturn(inactive);
        assertThrows(ApiException.class, () -> service.selectTeam(userId, lastId));
        verify(users, never()).updateLastTeam(any(), any());
    }

    private void available(UUID... ids) {
        when(members.selectByUserId(userId)).thenReturn(java.util.Arrays.stream(ids).map(id -> {
            TeamMemberEntity member = new TeamMemberEntity();
            member.setTeamId(id);
            member.setUserId(userId);
            member.setRole("TEAM_MEMBER");
            return member;
        }).toList());
        when(teams.selectBatchIds(anyCollection())).thenReturn(java.util.Arrays.stream(ids).map(this::team).toList());
    }

    private TeamEntity team(UUID id) {
        TeamEntity team = new TeamEntity();
        team.setId(id);
        team.setName("team");
        team.setOwnerUserId(UUID.randomUUID());
        team.setStatus("ACTIVE");
        team.setAvatarUrl("https://example.test/avatar.png");
        return team;
    }
}

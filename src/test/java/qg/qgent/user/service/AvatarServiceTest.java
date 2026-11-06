package qg.qgent.user.service;

import qg.qgent.support.TestIds;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import qg.qgent.attachment.dto.UploadCredential;
import qg.qgent.user.dto.AvatarConfirmRequest;
import qg.qgent.user.dto.AvatarConfirmResponse;
import qg.qgent.user.dto.AvatarCredentialResponse;
import qg.qgent.user.entity.UserEntity;
import qg.qgent.user.mapper.UserMapper;
import qg.qgent.common.storage.AvatarStorageService;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AvatarService 头像上传业务测试：凭证透传、对象键归属校验、确认落库并尽力删旧对象。 */
class AvatarServiceTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final AvatarStorageService storage = mock(AvatarStorageService.class);
    private final AvatarService service = new AvatarService(userMapper, storage);

    private final Long userId = TestIds.next();

    @BeforeEach
    void stubUser() {
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setAvatarUrl("https://bucket/old");
        user.setAvatarObjectKey("avatars/" + userId + "/old.jpg");
        when(userMapper.selectById(userId)).thenReturn(user);
    }

    @Test
    void credentialPassesThroughStorageResult() {
        String objectKey = "avatars/" + userId + "/abc.png";
        when(storage.createCredential(eq("avatars"), eq(userId), eq("image/png"), eq(1024L)))
                .thenReturn(new AvatarStorageService.AvatarCredential(objectKey,
                        new UploadCredential("https://put-url", "PUT", Map.of(), LocalDateTime.now())));

        AvatarCredentialResponse response = service.credential(userId, "image/png", 1024L);

        assertThat(response.getObjectKey()).isEqualTo(objectKey);
        assertThat(response.getUploadUrl()).isEqualTo("https://put-url");
    }

    @Test
    void confirmSetsAvatarUrlAndObjectKeyAndDeletesOld() {
        String newKey = "avatars/" + userId + "/new.jpg";
        when(storage.confirm("avatars", userId, newKey)).thenReturn("https://public/" + newKey);
        AvatarConfirmRequest request = new AvatarConfirmRequest();
        request.setObjectKey(newKey);

        AvatarConfirmResponse response = service.confirm(userId, request);

        assertThat(response.getAvatarUrl()).isEqualTo("https://public/" + newKey);
        verify(userMapper).selectById(userId);
        verify(userMapper).updateById(any(UserEntity.class));
        verify(storage).deleteObject("avatars/" + userId + "/old.jpg");
    }

    @Test
    void confirmDoesNotDeleteOldWhenSameAsNewOrNoPrevious() {
        when(storage.confirm("avatars", userId, "avatars/" + userId + "/old.jpg")).thenReturn("https://public/x");
        AvatarConfirmRequest request = new AvatarConfirmRequest();
        request.setObjectKey("avatars/" + userId + "/old.jpg");

        service.confirm(userId, request);

        org.mockito.Mockito.verify(storage, org.mockito.Mockito.never()).deleteObject(any());
    }
}

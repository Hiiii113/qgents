package qg.qgent.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import qg.qgent.common.web.ApiException;
import qg.qgent.user.dto.AvatarConfirmRequest;
import qg.qgent.user.dto.AvatarConfirmResponse;
import qg.qgent.user.dto.AvatarCredentialResponse;
import qg.qgent.user.entity.UserEntity;
import qg.qgent.user.mapper.UserMapper;
import qg.qgent.common.storage.AvatarStorageService;


/** 确认上传后保存用户头像 URL 和对象键，并尝试删除旧头像。 */
@Service
@RequiredArgsConstructor
public class AvatarService {

    private final UserMapper userMapper;
    private final AvatarStorageService storage;

    /** 为当前用户签发头像直传凭证。 */
    public AvatarCredentialResponse credential(Long userId, String mediaType, Long sizeBytes) {
        AvatarStorageService.AvatarCredential credential =
                storage.createCredential("avatars", userId, mediaType, sizeBytes);
        return new AvatarCredentialResponse(credential.objectKey(), credential.credential().getUploadUrl(),
                credential.credential().getMethod(), credential.credential().getHeaders(),
                credential.credential().getExpiresAt());
    }

    /** 确认头像上传并落库：校验对象属于当前用户、已真实上传，切换当前头像并尽力删除旧对象。 */
    @Transactional
    public AvatarConfirmResponse confirm(Long userId, AvatarConfirmRequest body) {
        String objectKey = body.getObjectKey();
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在");
        }
        String previousObjectKey = user.getAvatarObjectKey();
        String publicUrl = storage.confirm("avatars", userId, objectKey);
        user.setAvatarUrl(publicUrl);
        user.setAvatarObjectKey(objectKey);
        userMapper.updateById(user);
        if (previousObjectKey != null && !previousObjectKey.equals(objectKey)) {
            // 尽力删除旧头像对象；删除失败静默（不影响新头像已生效）
            storage.deleteObject(previousObjectKey);
        }
        return new AvatarConfirmResponse(publicUrl);
    }
}

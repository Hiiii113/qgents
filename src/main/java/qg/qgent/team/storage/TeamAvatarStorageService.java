package qg.qgent.team.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import qg.qgent.attachment.config.AliyunOssProperties;
import qg.qgent.attachment.dto.UploadCredential;
import java.util.UUID;
import qg.qgent.common.web.ApiException;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 签发团队头像 OSS 上传地址，确认后返回公共访问 URL。 */
@Component
@RequiredArgsConstructor
public class TeamAvatarStorageService {

    // 头像对象大小上限（字节）。
    static final long MAX_SIZE_BYTES = 5 * 1024 * 1024;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    private final ObjectProvider<OSS> ossProvider;
    private final AliyunOssProperties properties;

    /** 为团队签发头像直传凭证：对象键 {@code teams/{teamId}/{uuid}.{ext}}，预签名 PUT。 */
    public TeamAvatarCredential createCredential(Long teamId, String mediaType, Long sizeBytes) {
        OSS oss = requireConfigured();
        requirePublicUrl();
        String normalizedMediaType = mediaType == null ? "" : mediaType.trim().toLowerCase(Locale.ROOT);
        if (!normalizedMediaType.startsWith("image/")) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "AVATAR_MEDIA_TYPE_REJECTED",
                    "头像必须是图片（image/*）");
        }
        if (sizeBytes == null || sizeBytes <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AVATAR_SIZE_REQUIRED", "头像大小必须大于 0");
        }
        if (sizeBytes > MAX_SIZE_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "AVATAR_TOO_LARGE",
                    "头像大小超过上限 " + MAX_SIZE_BYTES + " 字节");
        }
        String extension = extensionFor(normalizedMediaType);
        String objectKey = "teams/" + teamId + "/" + UUID.randomUUID() + "." + extension;
        Date expiresAt = new Date(System.currentTimeMillis() + properties.getPresignExpirySeconds() * 1000L);
        URL url = oss.generatePresignedUrl(properties.getBucketName(), objectKey, expiresAt, HttpMethod.PUT);
        LocalDateTime expiry = LocalDateTime.ofInstant(expiresAt.toInstant(), ZoneOffset.UTC);
        return new TeamAvatarCredential(objectKey, new UploadCredential(url.toString(), "PUT", Map.of(), expiry));
    }

    /** 确认团队头像已上传并返回长期公共读 URL；校验对象存在且属于该团队命名空间。 */
    public String confirmAvatar(Long teamId, String objectKey) {
        requireConfigured();
        if (objectKey == null || objectKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AVATAR_OBJECT_KEY_REQUIRED", "缺少头像对象键");
        }
        String prefix = "teams/" + teamId + "/";
        if (!objectKey.startsWith(prefix)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AVATAR_OBJECT_KEY_FORBIDDEN",
                    "头像对象键不属于当前团队");
        }
        if (!ossProvider.getObject().doesObjectExist(properties.getBucketName(), objectKey)) {
            throw new ApiException(HttpStatus.CONFLICT, "AVATAR_NOT_UPLOADED",
                    "头像尚未上传到对象存储，请先上传再确认");
        }
        requirePublicUrl();
        return properties.getPublicBaseUrl().replaceAll("/+$", "") + "/" + objectKey;
    }

    private OSS requireConfigured() {
        if (!properties.isEnabled() || !properties.configured()) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "AVATAR_STORAGE_NOT_CONFIGURED",
                    "头像上传需要启用阿里云 OSS，当前未配置");
        }
        return ossProvider.getObject();
    }

    private void requirePublicUrl() {
        if (properties.getPublicBaseUrl() == null || properties.getPublicBaseUrl().isBlank()) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "AVATAR_PUBLIC_URL_NOT_CONFIGURED",
                    "未配置头像公共访问基础 URL（aliyun.oss.public-base-url）");
        }
    }

    private String extensionFor(String mediaType) {
        String subtype = mediaType.startsWith("image/") ? mediaType.substring("image/".length()).trim() : "";
        String ext = subtype.equals("jpeg") ? "jpg" : subtype;
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "AVATAR_MEDIA_TYPE_REJECTED",
                    "不支持的图片类型: " + mediaType);
        }
        return ext;
    }

    /** 团队头像直传凭证：服务端签发的对象键与其对应预签名上传凭证。 */
    public record TeamAvatarCredential(String objectKey, UploadCredential credential) {
    }
}

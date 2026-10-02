package qg.qgent.user.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import qg.qgent.attachment.config.AliyunOssProperties;
import qg.qgent.attachment.dto.UploadCredential;
import qg.qgent.common.util.UuidV7;
import qg.qgent.common.web.ApiException;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 签发用户头像 OSS 上传地址、确认上传并删除旧对象。 */
@Component
@RequiredArgsConstructor
public class AvatarStorageService {

    /** 头像对象大小上限（字节），前端应同步限制图片体积。 */
    static final long MAX_SIZE_BYTES = 5 * 1024 * 1024;

    /** 允许的头像扩展名白名单。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    private final ObjectProvider<OSS> ossProvider;
    private final AliyunOssProperties properties;

    /** 生成用户头像对象键并签发 OSS PUT 上传地址；图片最大 5 MB。 */
    public AvatarCredential createCredential(UUID userId, String mediaType, Long sizeBytes) {
        OSS oss = requireConfigured();
        requirePublicUrl(); // 缺公共读基础 URL 时先失败，避免客户端已经直传对象后才在 confirm 处报错
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
        String objectKey = "avatars/" + userId + "/" + UuidV7.next() + "." + extension;
        Date expiresAt = new Date(System.currentTimeMillis() + properties.getPresignExpirySeconds() * 1000L);
        URL url = oss.generatePresignedUrl(properties.getBucketName(), objectKey, expiresAt, HttpMethod.PUT);
        LocalDateTime expiry = LocalDateTime.ofInstant(expiresAt.toInstant(), ZoneOffset.UTC);
        return new AvatarCredential(objectKey, new UploadCredential(url.toString(), "PUT", Map.of(), expiry));
    }

    /** 确认头像已上传，返回其长期公共读 URL。校验对象确已存在于 OSS（未上传返回 409）。 */
    public String confirmAvatar(String objectKey) {
        requireConfigured();
        if (objectKey == null || objectKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AVATAR_OBJECT_KEY_REQUIRED", "缺少头像对象键");
        }
        if (!ossProvider.getObject().doesObjectExist(properties.getBucketName(), objectKey)) {
            throw new ApiException(HttpStatus.CONFLICT, "AVATAR_NOT_UPLOADED",
                    "头像尚未上传到对象存储，请先上传再确认");
        }
        requirePublicUrl();
        return properties.getPublicBaseUrl().replaceAll("/+$", "") + "/" + objectKey;
    }

    /** 尽力删除旧头像对象；OSS 未启用时 no-op。删除失败不抛出（不影响头像切换成功）。 */
    public void deleteObject(String objectKey) {
        if (!properties.isEnabled() || properties.getBucketName().isBlank()
                || objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            ossProvider.getObject().deleteObject(properties.getBucketName(), objectKey);
        } catch (RuntimeException e) {
            // 旧头像删除是尽力而为，失败不影响新头像已生效
        }
    }

    private OSS requireConfigured() {
        if (!properties.isEnabled() || !properties.configured()) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "AVATAR_STORAGE_NOT_CONFIGURED",
                    "头像上传需要启用阿里云 OSS，当前未配置");
        }
        return ossProvider.getObject();
    }

    /** 检查头像公共访问地址是否已配置。 */
    private void requirePublicUrl() {
        if (properties.getPublicBaseUrl() == null || properties.getPublicBaseUrl().isBlank()) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "AVATAR_PUBLIC_URL_NOT_CONFIGURED",
                    "未配置头像公共访问基础 URL（aliyun.oss.public-base-url），"
                            + "请设置环境变量 ALIYUN_OSS_PUBLIC_BASE_URL，如 https://<bucket>.<endpoint>");
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

    /** 头像直传凭证：服务端签发的对象键与其对应预签名上传凭证。 */
    public record AvatarCredential(String objectKey, UploadCredential credential) {
    }
}

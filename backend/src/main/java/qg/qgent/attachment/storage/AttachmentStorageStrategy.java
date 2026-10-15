package qg.qgent.attachment.storage;

import qg.qgent.attachment.dto.UploadCredential;
import qg.qgent.common.web.ApiException;
import org.springframework.http.HttpStatus;
import java.io.InputStream;
import java.util.OptionalLong;

/** 本地磁盘和 OSS 共用的附件存取接口；OSS 使用浏览器直传。 */
public interface AttachmentStorageStrategy {
    UploadCredential createCredential(String objectKey, String mediaType);

    /** 仅本地存储接收后端上传；必须完整写入并校验大小后才公开文件。 */
    default void storeBytes(String objectKey, InputStream bytes, long expectedSize, long maxSize) {
        throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_DIRECT_UPLOAD_REQUIRED", "请使用返回的上传地址上传文件");
    }

    OptionalLong objectSize(String objectKey);

    AttachmentContent loadContent(String objectKey, String fileName, String contentType);

    /** 本地磁盘不提供独立下载 URL，使用后端内容接口。 */
    default String createDownloadUrl(String objectKey, long expiresSeconds) {
        throw new UnsupportedOperationException("本地附件请通过内容接口下载");
    }
}

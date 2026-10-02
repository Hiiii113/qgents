package qg.qgent.attachment.storage;

import qg.qgent.attachment.dto.UploadCredential;
import java.io.InputStream;

/** 本地磁盘和 OSS 共用的附件存取接口。上传地址均指向后端 PUT 接口。 */
public interface AttachmentStorageStrategy {
    UploadCredential createCredential(String objectKey, String mediaType);

    void storeBytes(String objectKey, InputStream bytes, String contentType);

    boolean objectExists(String objectKey);

    AttachmentContent loadContent(String objectKey, String fileName, String contentType);

    /** 本地磁盘不提供独立下载 URL，使用后端内容接口。 */
    default String createDownloadUrl(String objectKey, long expiresSeconds) {
        throw new UnsupportedOperationException("本地附件请通过内容接口下载");
    }
}

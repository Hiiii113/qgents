package qg.qgent.attachment.storage;

import java.io.InputStream;
import lombok.Value;

/** 内容流及文件信息，调用方负责关闭流。 */
@Value
public class AttachmentContent {
    InputStream stream;
    String contentType;
    String fileName;
    Long sizeBytes;
}

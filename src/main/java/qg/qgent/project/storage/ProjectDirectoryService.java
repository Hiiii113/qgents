package qg.qgent.project.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 项目持久目录：一个项目一个目录，按项目 ID 命名，目录内的文件是记忆与后续共享资料的来源。 */
@Component
public class ProjectDirectoryService {

    private final Path root;

    public ProjectDirectoryService(@Value("${app.project.root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    /** 项目根目录，不存在时创建；已存在时保留原有文件。 */
    public Path projectRoot(Long projectId) {
        Path directory = root.resolve(projectId.toString());
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("项目目录创建失败: " + directory, e);
        }
        return directory;
    }
}

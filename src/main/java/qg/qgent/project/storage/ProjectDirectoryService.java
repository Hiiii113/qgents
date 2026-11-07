package qg.qgent.project.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 项目持久目录：一个项目一个目录，按项目 ID 命名。
 * <p>
 * 目录内 memory.md 是项目记忆，.git/ 保存各仓库共享的 bare Git 数据，
 * workspaces/ 保存每个 Workspace 的普通工作文件（每仓库一个 worktree）。
 */
@Component
public class ProjectDirectoryService {
    private static final Pattern WORKSPACE_DIRECTORY = Pattern.compile("default|\\d{1,19}");
    private static final Pattern REPOSITORY_ID = Pattern.compile("\\d{1,19}");

    private final Path root;
    private final Path dockerHostRoot;

    public ProjectDirectoryService(@Value("${app.project.root}") String root,
                                   @Value("${app.project.docker-host-root}") String dockerHostRoot) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.dockerHostRoot = Path.of(dockerHostRoot).toAbsolutePath().normalize();
    }

    /** 项目根目录，不存在时创建；已存在时保留原有文件。 */
    public Path projectRoot(Long projectId) {
        return create(root.resolve(projectId.toString()));
    }

    /** 项目共享 Git 数据目录，不存在时创建。 */
    public Path gitRoot(Long projectId) {
        return create(projectRoot(projectId).resolve(".git"));
    }

    /** 单个仓库的 bare Git 数据目录。 */
    public Path repositoryGit(Long projectId, Long providerRepositoryId) {
        return gitRoot(projectId).resolve(repositoryId(providerRepositoryId) + ".git");
    }

    /** Workspace 根目录，不存在时创建。 */
    public Path workspacesRoot(Long projectId) {
        return create(projectRoot(projectId).resolve("workspaces"));
    }

    /** 单个 Workspace 的目录，不存在时创建；directoryName 为 default 或 Workspace ID。 */
    public Path workspace(Long projectId, String directoryName) {
        return create(workspacesRoot(projectId).resolve(directory(directoryName)));
    }

    /** Workspace 内某仓库的 worktree 路径。 */
    public Path worktree(Long projectId, String directoryName, Long providerRepositoryId) {
        return workspace(projectId, directoryName).resolve(repositoryId(providerRepositoryId));
    }

    /** 受管理仓库的准备记录；位于项目 Git 目录内，不参与 worktree。 */
    public Path repositoryState(Long projectId, String directoryName, Long providerRepositoryId) {
        return gitRoot(projectId).resolve("qgents").resolve("workspaces")
                .resolve(directory(directoryName)).resolve(repositoryId(providerRepositoryId) + ".json");
    }

    /** Docker daemon 所在宿主机上同一项目目录的路径，用于创建 bind mount。 */
    public Path dockerHostProjectRoot(Long projectId) {
        return dockerHostRoot.resolve(projectId.toString());
    }

    /** 删除项目目录及其全部文件；目录不存在时什么都不做。 */
    public void delete(Long projectId) {
        Path directory = root.resolve(projectId.toString());
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (NoSuchFileException ignored) {
            // 项目从未写过文件，目录不存在
        } catch (IOException e) {
            throw new IllegalStateException("项目目录删除失败: " + directory, e);
        }
    }

    private Path create(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("项目目录创建失败: " + directory, e);
        }
        return directory;
    }

    private String directory(String directoryName) {
        if (directoryName == null || !WORKSPACE_DIRECTORY.matcher(directoryName).matches())
            throw new IllegalArgumentException("Workspace 目录名不合法");
        return directoryName;
    }

    private String repositoryId(Long providerRepositoryId) {
        if (providerRepositoryId == null || !REPOSITORY_ID.matcher(providerRepositoryId.toString()).matches())
            throw new IllegalArgumentException("仓库 ID 不合法");
        return providerRepositoryId.toString();
    }
}

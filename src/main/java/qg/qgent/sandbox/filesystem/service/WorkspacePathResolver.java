package qg.qgent.sandbox.filesystem.service;

import org.springframework.stereotype.Component;

import qg.qgent.sandbox.exception.SandboxException;
import qg.qgent.sandbox.config.SandboxProperties;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/**
 * 将 Workspace 存储标识解析为安全路径。
 */
@Component
public class WorkspacePathResolver {
    private static final Pattern STORAGE_KEY = Pattern.compile("workspaces/[0-9a-fA-F-]{36}");
    private final Path localRoot;
    private final Path dockerHostRoot;

    public WorkspacePathResolver(SandboxProperties properties) {
        localRoot = Path.of(properties.getWorkspaceLocalRoot()).toAbsolutePath().normalize();
        dockerHostRoot = Path.of(properties.getWorkspaceDockerHostRoot()).toAbsolutePath().normalize();
    }

    public Path resolveLocal(String storageKey) {
        validateStorageKey(storageKey);
        Path candidate = localRoot.resolve(relative(storageKey)).normalize();
        requireInside(candidate, localRoot);
        try {
            if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) throw invalid("Workspace 目录不存在");
            Path realRoot = localRoot.toRealPath();
            Path realCandidate = candidate.toRealPath();
            requireInside(realCandidate, realRoot);
            return realCandidate;
        } catch (SandboxException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("无法解析 Workspace 目录");
        }
    }

    public Path resolveDockerHost(String storageKey) {
        validateStorageKey(storageKey);
        Path candidate = dockerHostRoot.resolve(relative(storageKey)).normalize();
        requireInside(candidate, dockerHostRoot);
        return candidate;
    }

    private Path relative(String storageKey) {
        return Path.of(storageKey.substring("workspaces/".length()));
    }

    private void validateStorageKey(String storageKey) {
        if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches()) throw invalid("Workspace 存储标识不合法");
    }

    private void requireInside(Path candidate, Path root) {
        if (!candidate.startsWith(root)) throw invalid("Workspace 路径越界");
    }

    private SandboxException invalid(String message) {
        return new SandboxException(UNPROCESSABLE_ENTITY, "WORKSPACE_PATH_INVALID", message);
    }
}

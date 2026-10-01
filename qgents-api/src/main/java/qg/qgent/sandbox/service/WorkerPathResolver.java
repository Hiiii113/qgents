package qg.qgent.sandbox.service;
/** 文件路径始终相对于整个 AgentRun 工作区。 */
final class WorkerPathResolver {
    static Target resolve(SandboxSession session,String path) {
        return path == null || path.isBlank() ? null : new Target(path.replace('\\','/'));
    }
    record Target(String relativePath) { }
}

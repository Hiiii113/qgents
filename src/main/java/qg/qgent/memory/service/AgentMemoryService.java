package qg.qgent.memory.service;

import org.springframework.stereotype.Service;
import qg.qgent.project.storage.ProjectDirectoryService;

/** Agent 私有记忆：每个 Agent 在项目目录下只有一篇，它所在的各个群共享这一篇，只能由它自己维护。 */
@Service
public class AgentMemoryService {

    private final ProjectDirectoryService directories;
    private final MemoryDocument document;

    public AgentMemoryService(ProjectDirectoryService directories, MemoryDocument document) {
        this.directories = directories;
        this.document = document;
    }

    public String view(Long projectId, Long agentId) {
        return document.read(directories.agentMemory(projectId, agentId));
    }

    public void save(Long projectId, Long agentId, String content) {
        document.write(directories.agentMemory(projectId, agentId), content);
    }
}

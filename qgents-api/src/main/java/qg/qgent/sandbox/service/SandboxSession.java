package qg.qgent.sandbox.service;
import java.util.UUID;
public record SandboxSession(UUID taskId, UUID workspaceId, UUID sandboxId, String storageKey) { }

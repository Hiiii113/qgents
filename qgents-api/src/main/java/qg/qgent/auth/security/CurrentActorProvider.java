package qg.qgent.auth.security;

import java.util.UUID;

public interface CurrentActorProvider {
    UUID currentUserId();
}

package qg.qgent.chat.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 私聊对外契约：路径与前端联调时约定的 8 个入口一致。
 */
class DirectMessageControllerRouteTest {

    @Test
    void exposesTheAgreedDirectMessageRoutes() {
        Set<String> routes = new HashSet<>();
        for (Method method : DirectMessageController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(GetMapping.class)) {
                routes.add("GET " + method.getAnnotation(GetMapping.class).value()[0]);
            }
            if (method.isAnnotationPresent(PostMapping.class)) {
                routes.add("POST " + method.getAnnotation(PostMapping.class).value()[0]);
            }
        }

        assertEquals(
                Set.of(
                        "GET /projects/{projectId}/direct-messages",
                        "POST /projects/{projectId}/direct-messages",
                        "GET /projects/{projectId}/direct-messages/{conversationId}",
                        "GET /projects/{projectId}/direct-messages/{conversationId}/messages",
                        "GET /projects/{projectId}/direct-messages/{conversationId}/messages/incremental",
                        "GET /projects/{projectId}/direct-messages/{conversationId}/messages/{messageId}",
                        "POST /projects/{projectId}/direct-messages/{conversationId}/messages",
                        "POST /projects/{projectId}/direct-messages/{conversationId}/read"),
                routes);
    }
}

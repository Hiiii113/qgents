package qg.qgent.realtime.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.realtime.service.EventService;
import qg.qgent.realtime.service.RealtimeHub;
import qg.qgent.realtime.websocket.WebSocketRealtimeHandler;
import qg.qgent.team.mapper.TeamMemberMapper;

class RealtimeTransportWiringTest {

    @Test
    void eventServicePublishesThroughTheHub() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            registerMock(context, ProjectMemberMapper.class);
            registerMock(context, TeamMemberMapper.class);
            registerMock(context, ChatGroupMapper.class);
            context.register(RealtimeHub.class, WebSocketRealtimeHandler.class, EventService.class);
            context.refresh();

            assertNotNull(context.getBean(EventService.class));
            assertNotNull(context.getBean(RealtimeHub.class));
            assertNotNull(context.getBean(WebSocketRealtimeHandler.class));
        }
    }

    private static <T> void registerMock(
            AnnotationConfigApplicationContext context, Class<T> type) {
        context.registerBean(type, () -> mock(type));
    }
}

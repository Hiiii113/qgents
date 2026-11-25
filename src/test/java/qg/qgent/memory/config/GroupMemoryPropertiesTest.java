package qg.qgent.memory.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GroupMemoryPropertiesTest {
    @Test
    void defaultConfigurationIsValid() {
        assertDoesNotThrow(new GroupMemoryProperties()::validate);
    }

    @Test
    void invalidThresholdFailsFast() {
        GroupMemoryProperties properties = new GroupMemoryProperties();
        properties.setMaxAttempts(0);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    void longIdleFallbackMayNotExceedTheRegularThreshold() {
        GroupMemoryProperties properties = new GroupMemoryProperties();
        properties.setStaleMinMessages(properties.getMinMessages() + 1);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    void longIdleFallbackMustWaitAtLeastTheRegularIdleTime() {
        GroupMemoryProperties properties = new GroupMemoryProperties();
        properties.setStaleIdleMinutes(properties.getIdleMinutes() - 1);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    void forceThresholdMayNotBeLowerThanTheRegularThreshold() {
        GroupMemoryProperties properties = new GroupMemoryProperties();
        properties.setForceMessages(properties.getMinMessages() - 1);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }
}

package qg.qgent.memory.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}

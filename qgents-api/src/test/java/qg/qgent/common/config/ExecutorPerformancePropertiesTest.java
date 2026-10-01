package qg.qgent.common.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutorPerformancePropertiesTest {

    @Test
    void defaultPoolsAreValid() {
        ExecutorPerformanceProperties properties = new ExecutorPerformanceProperties();

        assertDoesNotThrow(() -> properties.getAgent().validate("orchestration"));
    }

    @Test
    void invalidPoolConfigurationFailsFast() {
        ExecutorPerformanceProperties.Pool pool = new ExecutorPerformanceProperties.Pool(4, 2, 100);

        assertThrows(IllegalArgumentException.class, () -> pool.validate("test"));
    }
}

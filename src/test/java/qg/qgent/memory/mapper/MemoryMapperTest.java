package qg.qgent.memory.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryMapperTest {
    @Test
    void lockQueriesReuseIdAndTagMappingsFromListQuery() {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(MemoryMapper.class);
        String namespace = MemoryMapper.class.getName();
        var mapping = configuration.getResultMap(namespace + ".memory");

        for (String method : new String[]{"listMemories", "selectApprovedForUpdate", "selectByIdForUpdate"}) {
            assertSame(mapping, configuration.getMappedStatement(namespace + "." + method).getResultMaps().getFirst());
        }
        assertEquals(6, mapping.getResultMappings().size());
        for (var field : mapping.getResultMappings()) {
            if ("tags".equals(field.getProperty())) {
                assertTrue(field.getTypeHandler() instanceof JacksonTypeHandler);
            }
        }
    }
}

package qg.qgent.memory.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.junit.jupiter.api.Test;
import qg.qgent.common.persistence.typehandler.UuidBinaryTypeHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryMapperTest {
    @Test
    void lockQueriesReuseUuidAndTagMappingsFromListQuery() {
        var configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(java.util.UUID.class, UuidBinaryTypeHandler.class);
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
            } else {
                assertTrue(field.getTypeHandler() instanceof UuidBinaryTypeHandler);
            }
        }
    }
}

package qg.qgent.skill.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.Test;
import qg.qgent.common.persistence.typehandler.UuidBinaryTypeHandler;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SkillMapperTest {
    @Test
    void listAndActivationShareMappingsAndListRestrictsUnpublishedSharedContent() {
        var configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidBinaryTypeHandler.class);
        configuration.addMapper(SkillMapper.class);
        String namespace = SkillMapper.class.getName();
        var list = configuration.getMappedStatement(namespace + ".listSkills");
        var activation = configuration.getMappedStatement(namespace + ".findVisiblePublishedById");
        assertThat(activation.getResultMaps().getFirst()).isSameAs(list.getResultMaps().getFirst());
        assertThat(list.getResultMaps().getFirst().getResultMappings()).hasSize(6);
        var sql = list.getBoundSql(Map.of("projectId", UUID.randomUUID(), "actor", UUID.randomUUID(), "admin", false));
        assertThat(sql.getSql()).contains("created_by = ? OR (visibility = 'PROJECT_SHARED' AND (status = 'PUBLISHED' OR ?))");
    }
}

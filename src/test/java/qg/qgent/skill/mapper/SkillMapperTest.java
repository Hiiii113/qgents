package qg.qgent.skill.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qg.qgent.support.MapperTestSupport;
import qg.qgent.support.TestIds;

class SkillMapperTest {
    @Test
    void listAndActivationShareMappingsAndListRestrictsUnpublishedSharedContent() {
        var configuration = MapperTestSupport.configuration(SkillMapper.class);
        String namespace = SkillMapper.class.getName();
        var list = configuration.getMappedStatement(namespace + ".listSkills");
        var activation = configuration.getMappedStatement(namespace + ".findVisiblePublishedById");
        assertThat(activation.getResultMaps().getFirst()).isSameAs(list.getResultMaps().getFirst());
        assertThat(list.getResultMaps().getFirst().getResultMappings())
                .anyMatch(field -> "id".equals(field.getProperty()))
                .anyMatch(field -> "tags".equals(field.getProperty())
                        && field.getTypeHandler() instanceof JacksonTypeHandler);
        var sql =
                list.getBoundSql(
                        Map.of(
                                "projectId",
                                TestIds.next(),
                                "actor",
                                TestIds.next(),
                                "admin",
                                false));
        assertThat(sql.getSql())
                .contains(
                        "created_by = ? OR (visibility = 'PROJECT_SHARED' AND (status = 'PUBLISHED' OR ?))");
    }
}

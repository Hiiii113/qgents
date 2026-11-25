package qg.qgent.project.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.support.MapperTestSupport;
import qg.qgent.support.TestIds;

class ProjectMapperTest {
    @Test
    void lockingQueriesLoadAvatarAndNullAvatarIsIncludedInGeneratedUpdate() {
        var configuration = MapperTestSupport.configuration(ProjectMapper.class);
        String namespace = ProjectMapper.class.getName();
        var statement = configuration.getMappedStatement(namespace + ".selectByIdForUpdate");
        assertThat(statement.getBoundSql(Map.of("projectId", TestIds.next())).getSql())
                .contains("avatar_url", "FOR UPDATE");
        assertThat(statement.getResultMaps().getFirst().getType()).isEqualTo(ProjectEntity.class);
        assertThat(configuration.isMapUnderscoreToCamelCase()).isTrue();
        ProjectEntity project = new ProjectEntity();
        project.setId(TestIds.next());
        project.setName("项目");
        var sql =
                configuration
                        .getMappedStatement(namespace + ".updateById")
                        .getBoundSql(Map.of("et", project));
        assertThat(sql.getSql()).contains("avatar_url=?");
        assertThat(sql.getParameterMappings())
                .anyMatch(parameter -> "et.avatarUrl".equals(parameter.getProperty()));
    }
}

package qg.qgent.project.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.Test;
import qg.qgent.common.persistence.typehandler.UuidBinaryTypeHandler;
import qg.qgent.project.entity.ProjectEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectMapperTest {
    @Test
    void lockingQueriesLoadAvatarAndNullAvatarIsIncludedInGeneratedUpdate() {
        var configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidBinaryTypeHandler.class);
        configuration.addMapper(ProjectMapper.class);
        String namespace = ProjectMapper.class.getName();
        for (String method : new String[]{"selectByIdForUpdate", "selectByTeamForUpdate"}) {
            var statement = configuration.getMappedStatement(namespace + "." + method);
            assertThat(statement.getBoundSql(Map.of("projectId", UUID.randomUUID(), "teamId", UUID.randomUUID()))
                    .getSql()).contains("avatar_url");
            assertThat(statement.getResultMaps().getFirst().getResultMappings())
                    .anyMatch(field -> "avatarUrl".equals(field.getProperty()));
        }
        ProjectEntity project = new ProjectEntity();
        project.setId(UUID.randomUUID());
        project.setName("项目");
        var sql = configuration.getMappedStatement(namespace + ".updateById")
                .getBoundSql(Map.of("et", project));
        assertThat(sql.getSql()).contains("avatar_url=?");
        assertThat(sql.getParameterMappings()).anyMatch(parameter -> "et.avatarUrl".equals(parameter.getProperty()));
    }
}

package qg.qgent.skill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;

import qg.qgent.skill.dto.ContextSkill;
import qg.qgent.common.persistence.typehandler.UuidBinaryTypeHandler;
import qg.qgent.skill.entity.SkillEntity;

import java.util.List;
import java.util.UUID;

/** Skill 数据访问。 */
@Mapper
public interface SkillMapper extends BaseMapper<SkillEntity> {

    /** 查询自己的 Skill、已发布共享内容；管理员可查看所有共享状态。 */
    @Select({"<script>",
            "SELECT * FROM skills WHERE project_id = #{projectId}",
            "AND (created_by = #{actor} OR (visibility = 'PROJECT_SHARED' AND (status = 'PUBLISHED' OR #{admin})))",
            "<if test='status != null'>AND status = #{status}</if>",
            "<if test='tag != null'>AND JSON_CONTAINS(tags, JSON_QUOTE(#{tag}))</if>",
            "ORDER BY updated_at DESC",
            "</script>"})
    @Results(id = "skill", value = {
            @Result(column = "id", property = "id", id = true, typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "project_id", property = "projectId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "created_by", property = "createdBy", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "submitted_by", property = "submittedBy", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "reviewer_id", property = "reviewerId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "tags", property = "tags", typeHandler = JacksonTypeHandler.class)
    })
    List<SkillEntity> listSkills(@Param("projectId") UUID projectId, @Param("actor") UUID actor,
                                 @Param("status") String status, @Param("tag") String tag, @Param("admin") boolean admin);

    /** 查询默认上下文可见的已发布 Skill 目录。刻意只投影 ID 与名称，不读取正文。 */
    @Select("SELECT id, name FROM skills WHERE project_id = #{projectId} "
            + "AND (visibility = 'PROJECT_SHARED' OR created_by = #{actor}) "
            + "AND status = 'PUBLISHED' ORDER BY updated_at DESC")
    @Results({
            @Result(column = "id", property = "id", id = true, typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "name", property = "name")
    })
    List<ContextSkill> listPublishedCatalog(@Param("projectId") UUID projectId, @Param("actor") UUID actor);

    /** 读取一条当前用户可见的已发布 Skill 正文，供显式激活使用。 */
    @Select("SELECT * FROM skills WHERE id = #{skillId} AND project_id = #{projectId} "
            + "AND (visibility = 'PROJECT_SHARED' OR created_by = #{actor}) "
            + "AND status = 'PUBLISHED'")
    @ResultMap("skill")
    SkillEntity findVisiblePublishedById(@Param("projectId") UUID projectId, @Param("actor") UUID actor,
                                         @Param("skillId") UUID skillId);
}

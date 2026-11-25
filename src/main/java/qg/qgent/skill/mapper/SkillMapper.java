package qg.qgent.skill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import qg.qgent.skill.dto.ContextSkill;
import qg.qgent.skill.entity.SkillEntity;

import java.util.List;

/**
 * Skill 数据访问。
 */
@Mapper
public interface SkillMapper extends BaseMapper<SkillEntity> {

    /**
     * 查询自己的 Skill、已发布共享内容；管理员可查看所有共享状态。
     */
    List<SkillEntity> listSkills(
        @Param("projectId") Long projectId,
        @Param("actor") Long actor,
        @Param("status") String status,
        @Param("tag") String tag,
        @Param("admin") boolean admin);

    /**
     * 查询默认上下文可见的已发布 Skill 目录，不读取正文。
     */
    List<ContextSkill> listPublishedCatalog(
        @Param("projectId") Long projectId, @Param("actor") Long actor);

    /**
     * 读取一条当前用户可见的已发布 Skill 正文，供显式激活使用。
     */
    SkillEntity findVisiblePublishedById(
        @Param("projectId") Long projectId,
        @Param("actor") Long actor,
        @Param("skillId") Long skillId);
}

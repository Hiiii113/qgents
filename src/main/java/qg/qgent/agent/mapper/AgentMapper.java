package qg.qgent.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import qg.qgent.agent.entity.AgentEntity;

@Mapper
public interface AgentMapper extends BaseMapper<AgentEntity> {

    /** 项目默认 Agent；项目创建时写入，唯一索引保证每项目只有一条。 */
    @Select("SELECT * FROM agents WHERE project_id = #{projectId} AND default_agent = 1")
    AgentEntity selectDefaultAgent(@Param("projectId") Long projectId);
}

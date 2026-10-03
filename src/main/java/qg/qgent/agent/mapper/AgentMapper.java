package qg.qgent.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import qg.qgent.agent.entity.AgentEntity;

/** 团队 Agent 数据访问。 */
@Mapper
public interface AgentMapper extends BaseMapper<AgentEntity> {
}

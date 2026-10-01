package qg.qgent.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import qg.qgent.agent.entity.AgentEntity;

/**
 * Data access for team-scoped Agent identities.
 */
@Mapper
public interface AgentMapper extends BaseMapper<AgentEntity> {
}

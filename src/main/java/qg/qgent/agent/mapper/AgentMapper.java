package qg.qgent.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import qg.qgent.agent.entity.AgentEntity;

import java.util.List;

/**
 * 项目内 Agent 数据访问；申请状态只在服务端核验管理员身份后推进。
 */
@Mapper
public interface AgentMapper extends BaseMapper<AgentEntity> {

    /**
     * 项目默认 Agent；项目创建时写入，唯一索引保证每项目只有一条。
     */
    AgentEntity selectDefaultAgent(@Param("projectId") Long projectId);

    /**
     * 项目内已启用的 Agent：只有它们能邀请入群和运行。
     */
    List<AgentEntity> selectActiveByProject(@Param("projectId") Long projectId);

    /**
     * 项目内待审批的申请。
     */
    List<AgentEntity> selectPendingByProject(@Param("projectId") Long projectId);

    /**
     * 计入数量上限的 Agent：待审批申请也算，被拒绝的申请释放名额。
     * <p>
     * 加锁读：调用方在项目行锁内计数，但事务此前已做过普通读，快照可能落后于刚提交的申请，
     * 只有 FOR UPDATE 才能读到最新已提交行并锁住该项目的插入范围。
     */
    int countCountedByProject(@Param("projectId") Long projectId);

    /**
     * 审批通过；只在仍待审批时生效，重复审批得到 0。
     */
    int activate(@Param("id") Long id, @Param("approvedBy") Long approvedBy);

    /**
     * 管理员拒绝申请；被拒绝的申请不能激活，也不再占用数量上限。
     */
    int reject(@Param("id") Long id, @Param("approvedBy") Long approvedBy);
}

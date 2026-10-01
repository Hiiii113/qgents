package qg.qgent.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import qg.qgent.common.persistence.typehandler.UuidBinaryTypeHandler;
import qg.qgent.memory.entity.MemoryEntity;

import java.util.List;
import java.util.UUID;

/** Memory 数据访问。 */
@Mapper
public interface MemoryMapper extends BaseMapper<MemoryEntity> {

    /** 查询可见 Memory；未指定状态时返回全部可见状态，支持标签过滤。 */
    @Select({"<script>",
            "SELECT * FROM memories WHERE project_id = #{projectId}",
            "AND (status = 'APPROVED' OR created_by = #{actor} OR #{isAdmin})",
            "<if test='status != null'>AND status = #{status}</if>",
            "<if test='tag != null'>AND JSON_CONTAINS(tags, JSON_QUOTE(#{tag}))</if>",
            "ORDER BY updated_at DESC",
            "</script>"})
    @Results(id = "memory", value = {
            @Result(column = "id", property = "id", id = true, typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "project_id", property = "projectId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "created_by", property = "createdBy", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "submitted_by", property = "submittedBy", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "reviewer_id", property = "reviewerId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "tags", property = "tags", typeHandler = JacksonTypeHandler.class)
    })
    List<MemoryEntity> listMemories(@Param("projectId") UUID projectId, @Param("actor") UUID actor,
                                    @Param("isAdmin") boolean isAdmin, @Param("status") String status, @Param("tag") String tag);

    /** 先锁定项目，再读取已批准内容以计算发布预算；字段映射复用列表查询。 */
    @Select("SELECT * FROM memories WHERE project_id = #{projectId} AND status = 'APPROVED' FOR UPDATE")
    @ResultMap("memory")
    List<MemoryEntity> selectApprovedForUpdate(@Param("projectId") UUID projectId);

    /** 锁定读取单条 Memory，用于在状态迁移前获得当前已提交状态。 */
    @Select("SELECT * FROM memories WHERE id = #{memoryId} FOR UPDATE")
    @ResultMap("memory")
    MemoryEntity selectByIdForUpdate(@Param("memoryId") UUID memoryId);
}

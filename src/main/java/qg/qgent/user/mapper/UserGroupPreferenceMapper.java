package qg.qgent.user.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户 × 群置顶偏好数据访问（表 user_group_preference）。
 */
@Mapper
public interface UserGroupPreferenceMapper {

    /**
     * 设置 / 取消某用户对某群的置顶状态。
     */
    void upsertPin(
        @Param("userId") Long userId,
        @Param("groupId") Long groupId,
        @Param("pinned") boolean pinned);

    /**
     * 批量取某用户在一组群中的置顶群 ID（仅 pinned = true 者），一次 IN 查询避免 N+1。
     */
    List<Long> selectPinnedGroupIds(
        @Param("userId") Long userId, @Param("groupIds") List<Long> groupIds);
}

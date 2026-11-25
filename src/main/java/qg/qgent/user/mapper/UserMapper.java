package qg.qgent.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import qg.qgent.user.entity.UserEntity;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    int updateLastTeam(@Param("userId") Long userId, @Param("teamId") Long teamId);

    /**
     * 锁定用户行，用作该用户通知序号分配的跨实例串行化锚点。
     */
    UserEntity selectByIdForUpdate(@Param("userId") Long userId);
}

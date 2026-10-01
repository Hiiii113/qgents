package qg.qgent.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import qg.qgent.auth.entity.RefreshTokenEntity;

@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshTokenEntity> {
}

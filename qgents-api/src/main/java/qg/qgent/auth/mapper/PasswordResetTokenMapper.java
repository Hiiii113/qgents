package qg.qgent.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import qg.qgent.auth.entity.PasswordResetTokenEntity;

@Mapper
public interface PasswordResetTokenMapper extends BaseMapper<PasswordResetTokenEntity> {
}

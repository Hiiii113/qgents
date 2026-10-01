package qg.qgent.github.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import qg.qgent.github.entity.GitHubRepositoryEntity;

@Mapper
public interface GitHubRepositoryMapper extends BaseMapper<GitHubRepositoryEntity> {
}

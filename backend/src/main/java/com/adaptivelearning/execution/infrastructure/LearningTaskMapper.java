package com.adaptivelearning.execution.infrastructure;

import com.adaptivelearning.execution.domain.LearningTaskEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LearningTaskMapper extends BaseMapper<LearningTaskEntity> {
}


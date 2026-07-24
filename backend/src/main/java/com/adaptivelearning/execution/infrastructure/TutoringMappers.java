package com.adaptivelearning.execution.infrastructure;

import com.adaptivelearning.execution.domain.TutoringMessageEntity;
import com.adaptivelearning.execution.domain.TutoringSessionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

public final class TutoringMappers {
    private TutoringMappers() {
    }

    @Mapper
    public interface TutoringSessionMapper extends BaseMapper<TutoringSessionEntity> {
    }

    @Mapper
    public interface TutoringMessageMapper extends BaseMapper<TutoringMessageEntity> {
    }
}

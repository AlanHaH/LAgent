package com.adaptivelearning.planning.infrastructure;

import com.adaptivelearning.planning.domain.*;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.time.Instant;

public final class PlanningMappers {
    private PlanningMappers() {}
    @Mapper public interface PlanMapper extends BaseMapper<LearningPlanEntity> {}
    @Mapper public interface PlanVersionMapper extends BaseMapper<PlanVersionEntity> {}
    @Mapper public interface PlanStageMapper extends BaseMapper<PlanStageEntity> {}
    @Mapper public interface PlanChangeMapper extends BaseMapper<PlanChangeItemEntity> {}
    @Mapper public interface PlanValidationMapper extends BaseMapper<PlanValidationResultEntity> {}
    @Mapper public interface PlanConfirmationMapper extends BaseMapper<PlanConfirmationEntity> {}
    @Mapper public interface PlanningJobMapper extends BaseMapper<PlanningJobEntity> {}
    @Mapper public interface IdempotencyMapper extends BaseMapper<IdempotencyRecordEntity> {}
    @Mapper public interface OutboxMapper extends BaseMapper<OutboxEventEntity> {}
    @Mapper public interface PublicationMapper {
        @Select("SELECT plan_version_id FROM plan_publication WHERE plan_id=#{planId} FOR UPDATE")
        Long lockCurrent(@Param("planId") long planId);
        @Insert("""
          INSERT INTO plan_publication(plan_id,plan_version_id,published_at) VALUES(#{planId},#{versionId},#{at})
          ON DUPLICATE KEY UPDATE plan_version_id=VALUES(plan_version_id),published_at=VALUES(published_at)
          """)
        int upsert(@Param("planId") long planId,@Param("versionId") long versionId,@Param("at") Instant at);
    }
}


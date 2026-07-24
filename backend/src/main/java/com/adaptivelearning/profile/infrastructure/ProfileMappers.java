package com.adaptivelearning.profile.infrastructure;

import com.adaptivelearning.profile.domain.*;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

public final class ProfileMappers {
    private ProfileMappers() {}

    @Mapper public interface UserProfileMapper extends BaseMapper<UserProfileEntity> {}
    @Mapper public interface ProfileDirectionMapper extends BaseMapper<ProfileDirectionEntity> {}
    @Mapper public interface LearningPreferenceMapper extends BaseMapper<LearningPreferenceEntity> {}
    @Mapper public interface AvailabilityRuleMapper extends BaseMapper<AvailabilityRuleEntity> {}
    @Mapper public interface AvailabilityExceptionMapper extends BaseMapper<AvailabilityExceptionEntity> {}
    @Mapper public interface SelfAssessmentMapper extends BaseMapper<SelfAssessmentEntity> {}
    @Mapper public interface ProfileVersionMapper extends BaseMapper<ProfileVersionEntity> {}
    @Mapper public interface ProfileGenerationJobMapper extends BaseMapper<ProfileGenerationJobEntity> {}
    @Mapper public interface ProfileInterviewSessionMapper extends BaseMapper<ProfileInterviewSessionEntity> {}
    @Mapper public interface ProfileInterviewMessageMapper extends BaseMapper<ProfileInterviewMessageEntity> {}
}

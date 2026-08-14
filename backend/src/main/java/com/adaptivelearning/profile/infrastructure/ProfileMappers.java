package com.adaptivelearning.profile.infrastructure;

import com.adaptivelearning.profile.domain.*;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public final class ProfileMappers {
    private ProfileMappers() {}

    @Mapper public interface UserProfileMapper extends BaseMapper<UserProfileEntity> {
        @Select("SELECT * FROM user_profile WHERE id=#{id} AND deleted_at IS NULL FOR UPDATE")
        UserProfileEntity selectByIdForUpdate(@Param("id") long id);

        @Select("SELECT * FROM user_profile WHERE user_id=#{userId} AND deleted_at IS NULL FOR UPDATE")
        UserProfileEntity selectByUserIdForUpdate(@Param("userId") long userId);
    }
    @Mapper public interface ProfileDirectionMapper extends BaseMapper<ProfileDirectionEntity> {}
    @Mapper public interface LearningPreferenceMapper extends BaseMapper<LearningPreferenceEntity> {}
    @Mapper public interface AvailabilityRuleMapper extends BaseMapper<AvailabilityRuleEntity> {}
    @Mapper public interface AvailabilityExceptionMapper extends BaseMapper<AvailabilityExceptionEntity> {}
    @Mapper public interface SelfAssessmentMapper extends BaseMapper<SelfAssessmentEntity> {}
    @Mapper public interface ProfileVersionMapper extends BaseMapper<ProfileVersionEntity> {}
    @Mapper public interface ProfileGenerationJobMapper extends BaseMapper<ProfileGenerationJobEntity> {
        @Select("SELECT * FROM profile_generation_job WHERE id=#{id} FOR UPDATE")
        ProfileGenerationJobEntity selectByIdForUpdate(@Param("id") long id);
    }
    @Mapper public interface ProfileInterviewSessionMapper extends BaseMapper<ProfileInterviewSessionEntity> {}
    @Mapper public interface ProfileInterviewMessageMapper extends BaseMapper<ProfileInterviewMessageEntity> {}
}

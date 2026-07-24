package com.adaptivelearning.evaluation.infrastructure;

import com.adaptivelearning.evaluation.domain.*;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

public final class EvaluationMappers {
    private EvaluationMappers() {
    }

    @Mapper
    public interface QuestionMapper extends BaseMapper<QuestionEntity> {
    }

    @Mapper
    public interface QuestionVersionMapper extends BaseMapper<QuestionVersionEntity> {
    }

    @Mapper
    public interface AssessmentMapper extends BaseMapper<AssessmentEntity> {
    }

    @Mapper
    public interface AssessmentQuestionMapper extends BaseMapper<AssessmentQuestionEntity> {
    }

    @Mapper
    public interface AttemptMapper extends BaseMapper<AssessmentAttemptEntity> {
        @Select("SELECT * FROM assessment_attempt WHERE id=#{id} FOR UPDATE")
        AssessmentAttemptEntity lock(@Param("id") long id);
    }

    @Mapper
    public interface AnswerMapper extends BaseMapper<AttemptAnswerEntity> {
    }

    @Mapper
    public interface MasteryEvidenceMapper extends BaseMapper<MasteryEvidenceEntity> {
    }

    @Mapper
    public interface MasteryMapper extends BaseMapper<KnowledgeMasteryEntity> {
    }

    @Mapper
    public interface WrongQuestionMapper extends BaseMapper<WrongQuestionEntity> {
    }

    @Mapper
    public interface ReportMapper extends BaseMapper<StudyReportEntity> {
    }
}

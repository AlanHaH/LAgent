package com.adaptivelearning.evaluation.application;

import com.adaptivelearning.evaluation.domain.*;
import com.adaptivelearning.evaluation.infrastructure.EvaluationMappers.*;
import com.adaptivelearning.planning.application.IdempotencyService;
import com.adaptivelearning.planning.domain.IdempotencyRecordEntity;
import com.adaptivelearning.shared.api.PageResponse;
import com.adaptivelearning.shared.ai.AiModelClient;
import com.adaptivelearning.shared.ai.AiModelException;
import com.adaptivelearning.shared.ai.PythonAiServiceClient;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class AssessmentService {
    private static final Set<String> QUESTION_TYPES = Set.of("SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE",
            "FILL_BLANK", "SHORT_ANSWER", "ESSAY");
    private final QuestionMapper questionMapper;
    private final QuestionVersionMapper questionVersionMapper;
    private final AssessmentMapper assessmentMapper;
    private final AssessmentQuestionMapper assessmentQuestionMapper;
    private final AttemptMapper attemptMapper;
    private final AnswerMapper answerMapper;
    private final MasteryEvidenceMapper evidenceMapper;
    private final MasteryMapper masteryMapper;
    private final WrongQuestionMapper wrongMapper;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final IdempotencyService idempotency;
    private final PythonAiServiceClient pythonAi;
    private final PlatformTransactionManager transactionManager;
    @Autowired @Qualifier("aiBackgroundExecutor")
    private Executor aiBackgroundExecutor;

    public record QuestionInput(String type, String stem, List<String> options, Object answer,
                                Map<String, Object> rubric, String analysis, int difficulty,
                                List<Long> knowledgePointIds, String visibility) {
    }

    public record QuestionView(String publicId, String type, String stem, List<String> options, String analysis,
                               int difficulty, List<Long> knowledgePointIds, String status, int version) {
    }

    public record QuestionRef(String questionId, BigDecimal score) {
    }

    public record AssessmentInput(String type, String title, int durationMinutes, int maxAttempts, BigDecimal passScore,
                                  List<QuestionRef> questions) {
    }

    public record AssessmentDetail(AssessmentEntity assessment, List<Map<String, Object>> questions) {
    }

    public record AttemptResult(AssessmentAttemptEntity attempt, List<Map<String, Object>> answers) {
    }

    @Transactional
    public QuestionEntity createQuestion(QuestionInput i, boolean publicQuestion) {
        validateQuestion(i);
        long user = SecurityUtils.currentUserId();
        QuestionEntity q = new QuestionEntity();
        q.setPublicId(UUID.randomUUID().toString());
        q.setOwnerUserId(publicQuestion ? null : user);
        q.setVisibility(publicQuestion ? "PUBLIC" : "PRIVATE");
        q.setCurrentVersionNo(1);
        q.setStatus(publicQuestion ? "PUBLISHED" : "DRAFT");
        q.setSourceType("USER");
        questionMapper.insert(q);
        QuestionVersionEntity v = new QuestionVersionEntity();
        v.setQuestionId(q.getId());
        v.setVersionNo(1);
        apply(v, i);
        questionVersionMapper.insert(v);
        double allocation = 1d / i.knowledgePointIds().size();
        for (Long kp : i.knowledgePointIds())
            jdbc.update("INSERT INTO question_knowledge_point(question_version_id,knowledge_point_id,allocation) VALUES(?,?,?)", v.getId(), kp, BigDecimal.valueOf(allocation));
        return q;
    }

    public QuestionView question(String id, boolean includeAnswer) {
        QuestionEntity q = accessibleQuestion(id);
        QuestionVersionEntity v = currentVersion(q);
        List<Long> kp = jdbc.query("SELECT knowledge_point_id FROM question_knowledge_point WHERE question_version_id=?", (rs, row) -> rs.getLong(1), v.getId());
        return new QuestionView(q.getPublicId(), v.getType(), v.getStem(), read(v.getOptionsJson(), new TypeReference<>() {
        }), includeAnswer ? v.getAnalysis() : null, v.getDifficulty(), kp, q.getStatus(), q.getVersion());
    }

    @Transactional
    public AssessmentEntity create(AssessmentInput i) {
        if (i.questions() == null || i.questions().isEmpty() || i.durationMinutes() < 1 || i.durationMinutes() > 240 || i.maxAttempts() < 1)
            bad("评估参数不合法");
        AssessmentEntity a = new AssessmentEntity();
        a.setPublicId(UUID.randomUUID().toString());
        a.setOwnerUserId(SecurityUtils.currentUserId());
        a.setType(i.type());
        a.setTitle(i.title());
        a.setStatus("PUBLISHED");
        a.setDurationMinutes(i.durationMinutes());
        a.setMaxAttempts(i.maxAttempts());
        a.setPassScore(i.passScore());
        a.setScopeJson(toJson(Map.of("source", "USER_SELECTION")));
        BigDecimal total = i.questions().stream().map(QuestionRef::score).reduce(BigDecimal.ZERO, BigDecimal::add);
        a.setTotalScore(total);
        if (i.passScore().compareTo(total) > 0) bad("及格分不能超过总分");
        assessmentMapper.insert(a);
        int seq = 1;
        for (QuestionRef ref : i.questions()) {
            QuestionEntity q = accessibleQuestion(ref.questionId());
            QuestionVersionEntity v = currentVersion(q);
            AssessmentQuestionEntity aq = new AssessmentQuestionEntity();
            aq.setAssessmentId(a.getId());
            aq.setSequenceNo(seq++);
            aq.setQuestionVersionId(v.getId());
            aq.setScore(ref.score());
            aq.setSnapshotJson(toJson(Map.of("questionId", q.getPublicId(), "version", v.getVersionNo(), "type", v.getType(), "stem", v.getStem(), "options", read(v.getOptionsJson(), new TypeReference<List<String>>() {
            }), "difficulty", v.getDifficulty())));
            assessmentQuestionMapper.insert(aq);
        }
        return a;
    }

    @Transactional
    public AssessmentEntity diagnostic(long directionId, int durationMinutes, int difficulty) {
        long user = SecurityUtils.currentUserId();
        // 只从用户画像声明的方向抽题：先取画像内有效的目录方向，再和所选方向聚合求交集
        List<Long> profileDirs = jdbc.query("""
                SELECT pd.direction_id FROM user_profile_direction pd
                JOIN user_profile p ON p.id = pd.profile_id
                WHERE p.user_id = ? AND pd.status = 'ACTIVE' AND pd.direction_id IS NOT NULL
                """, (rs, row) -> rs.getLong(1), user);
        if (profileDirs.isEmpty())
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "请先完善学习画像并选择学习方向，才能生成诊断");
        String profileIn = profileDirs.stream().map(i -> "?").collect(Collectors.joining(","));
        // 父方向自动聚合其下所有子方向的题目：选择「计算机科学与技术」会覆盖 Java/前端/AI 三块，避免父方向抽不到题
        List<Map<String, Object>> rows = jdbc.query("""
                WITH RECURSIVE req_tree AS (
                    SELECT id FROM learning_direction WHERE id=? AND deleted_at IS NULL
                    UNION ALL
                    SELECT d.id FROM learning_direction d JOIN req_tree t ON d.parent_id=t.id
                    WHERE d.deleted_at IS NULL
                ),
                profile_tree AS (
                    SELECT id FROM learning_direction WHERE id IN (%s)
                    UNION ALL
                    SELECT d.id FROM learning_direction d JOIN profile_tree t ON d.parent_id=t.id
                    WHERE d.deleted_at IS NULL
                )
                -- MySQL 8：DISTINCT 查询的 ORDER BY 列必须在 SELECT 列表中，因此把 v.difficulty 一并选出
                -- kp.direction_id 同时落在所选方向树和画像方向树内，非画像方向的题不会进入诊断
                SELECT DISTINCT q.public_id, v.difficulty FROM question q
                JOIN question_version v ON v.question_id=q.id AND v.version_no=q.current_version_no
                JOIN question_knowledge_point qk ON qk.question_version_id=v.id
                JOIN knowledge_point kp ON kp.id=qk.knowledge_point_id
                WHERE q.status='PUBLISHED' AND q.visibility='PUBLIC'
                  AND kp.direction_id IN (SELECT id FROM req_tree)
                  AND kp.direction_id IN (SELECT id FROM profile_tree)
                  AND v.difficulty BETWEEN ? AND ?
                ORDER BY v.difficulty LIMIT ?
                """.formatted(profileIn), (rs, row) -> Map.of("id", rs.getString(1)),
                // 参数顺序与 SQL 占位符一致：req_tree.id -> profileIn 各方向 -> difficulty 下/上限 -> LIMIT
                Stream.concat(Stream.of((Object) directionId),
                        Stream.concat(profileDirs.stream().map(Object.class::cast),
                                Stream.of((Object) Math.max(1, difficulty - 1),
                                        (Object) Math.min(5, difficulty + 1),
                                        (Object) Math.max(1, durationMinutes / 3)))).toArray());
        if (rows.isEmpty())
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "该方向暂无可用诊断题，请选择画像内的方向");
        List<QuestionRef> refs = rows.stream().map(r -> new QuestionRef((String) r.get("id"), BigDecimal.TEN)).toList();
        return create(new AssessmentInput("DIAGNOSTIC", "能力诊断", durationMinutes, 1, BigDecimal.valueOf(refs.size() * 6L), refs));
    }

    public PageResponse<AssessmentEntity> list(int page, int pageSize) {
        long user = SecurityUtils.currentUserId();
        var q = new LambdaQueryWrapper<AssessmentEntity>().and(x -> x.eq(AssessmentEntity::getOwnerUserId, user).or().isNull(AssessmentEntity::getOwnerUserId)).eq(AssessmentEntity::getStatus, "PUBLISHED").orderByDesc(AssessmentEntity::getCreatedAt);
        Page<AssessmentEntity> p = assessmentMapper.selectPage(Page.of(page, Math.min(100, pageSize)), q);
        // 补充当前用户对各评估的最近一次作答状态，供前端区分 开始 / 继续 / 查看结果
        List<AssessmentEntity> records = p.getRecords();
        if (!records.isEmpty()) {
            List<Long> ids = records.stream().map(AssessmentEntity::getId).toList();
            String in = ids.stream().map(i -> "?").collect(Collectors.joining(","));
            Map<Long, AssessmentAttemptEntity> latest = new HashMap<>();
            jdbc.query("""
                    SELECT at.assessment_id, at.public_id, at.status, at.started_at, ass.duration_minutes
                    FROM assessment_attempt at
                    JOIN assessment ass ON ass.id = at.assessment_id
                    WHERE at.user_id=? AND at.deleted_at IS NULL AND at.assessment_id IN (%s)
                    ORDER BY at.started_at DESC
                    """.formatted(in),
                rs -> {
                    long assessmentId = rs.getLong(1);
                    String attemptPublicId = rs.getString(2);
                    String attemptStatus = rs.getString(3);
                    Instant startedAt = rs.getTimestamp(4).toInstant();
                    int durationMinutes = rs.getInt(5);
                    latest.computeIfAbsent(assessmentId, k -> {
                        AssessmentAttemptEntity at = new AssessmentAttemptEntity();
                        at.setPublicId(attemptPublicId);
                        at.setStatus(attemptStatus);
                        return at;
                    });
                    // 进行中的作答若已超过评估时长，标记 EXPIRED：前端显示「已超时」而非可点击的「继续」
                    if ("IN_PROGRESS".equals(attemptStatus)
                            && startedAt.plus(Duration.ofMinutes(durationMinutes)).isBefore(Instant.now())) {
                        latest.get(assessmentId).setStatus("EXPIRED");
                    }
                },
                Stream.concat(Stream.of(user), ids.stream()).toArray());
            for (AssessmentEntity a : records) {
                AssessmentAttemptEntity at = latest.get(a.getId());
                if (at != null) {
                    a.setLastAttemptPublicId(at.getPublicId());
                    a.setLastAttemptStatus(at.getStatus());
                }
            }
        }
        return new PageResponse<>(p.getRecords(), p.getTotal(), page, Math.min(100, pageSize));
    }

    public AssessmentDetail detail(String id) {
        AssessmentEntity a = accessibleAssessment(id);
        List<Map<String, Object>> questions = assessmentQuestionMapper.selectList(new LambdaQueryWrapper<AssessmentQuestionEntity>().eq(AssessmentQuestionEntity::getAssessmentId, a.getId()).orderByAsc(AssessmentQuestionEntity::getSequenceNo)).stream().map(q -> read(q.getSnapshotJson(), new TypeReference<Map<String, Object>>() {
        })).toList();
        return new AssessmentDetail(a, questions);
    }

    @Transactional
    public AssessmentAttemptEntity start(String assessmentId) {
        AssessmentEntity a = accessibleAssessment(assessmentId);
        long user = SecurityUtils.currentUserId();
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM assessment_attempt WHERE assessment_id=? AND user_id=? AND deleted_at IS NULL", Integer.class, a.getId(), user);
        if (count != null && count >= a.getMaxAttempts())
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "已达到最大尝试次数");
        AssessmentAttemptEntity at = new AssessmentAttemptEntity();
        at.setPublicId(UUID.randomUUID().toString());
        at.setAssessmentId(a.getId());
        at.setUserId(user);
        at.setAttemptNo((count == null ? 0 : count) + 1);
        at.setStatus("IN_PROGRESS");
        at.setStartedAt(Instant.now());
        at.setGradingVersion(0);
        attemptMapper.insert(at);
        return at;
    }

    @Transactional
    public AttemptAnswerEntity saveAnswer(String attemptId, int sequence, Object answer) {
        AssessmentAttemptEntity at = ownedAttempt(attemptId);
        if (!"IN_PROGRESS".equals(at.getStatus()))
            throw new BusinessException(ErrorCode.ASSESSMENT_ALREADY_SUBMITTED, "交卷后不能修改答案");
        AssessmentEntity a = assessmentMapper.selectById(at.getAssessmentId());
        if (Instant.now().isAfter(at.getStartedAt().plus(Duration.ofMinutes(a.getDurationMinutes()))))
            throw new BusinessException(ErrorCode.ASSESSMENT_ALREADY_SUBMITTED, "评估已超时");
        AssessmentQuestionEntity aq = assessmentQuestionMapper.selectOne(new LambdaQueryWrapper<AssessmentQuestionEntity>().eq(AssessmentQuestionEntity::getAssessmentId, a.getId()).eq(AssessmentQuestionEntity::getSequenceNo, sequence));
        if (aq == null) notFound();
        AttemptAnswerEntity entity = answerMapper.selectOne(new LambdaQueryWrapper<AttemptAnswerEntity>().eq(AttemptAnswerEntity::getAttemptId, at.getId()).eq(AttemptAnswerEntity::getSequenceNo, sequence));
        if (entity == null) {
            entity = new AttemptAnswerEntity();
            entity.setPublicId(UUID.randomUUID().toString());
            entity.setAttemptId(at.getId());
            entity.setSequenceNo(sequence);
            entity.setGradingStatus("PENDING");
        }
        entity.setAnswerJson(toJson(answer));
        entity.setSavedAt(Instant.now());
        if (entity.getId() == null) answerMapper.insert(entity);
        else answerMapper.updateById(entity);
        return entity;
    }

    public AssessmentAttemptEntity submit(String attemptId, String key) {
        if (key == null || key.isBlank()) bad("缺少 Idempotency-Key");
        long user = SecurityUtils.currentUserId();
        SubmitWork work = new TransactionTemplate(transactionManager).execute(status -> {
            IdempotencyRecordEntity old = idempotency.find(user, key, attemptId);
            if (old != null) return new SubmitWork(ownedAttempt(old.getResponseRef()), false);
            AssessmentAttemptEntity probe = ownedAttempt(attemptId);
            AssessmentAttemptEntity at = attemptMapper.lock(probe.getId());
            if (!"IN_PROGRESS".equals(at.getStatus())) return new SubmitWork(at, false);
            at.setStatus("SUBMITTED");
            at.setSubmittedAt(Instant.now());
            attemptMapper.updateById(at);
            idempotency.save(user, key, attemptId, at.getPublicId());
            return new SubmitWork(at, true);
        });
        if (work == null) throw new IllegalStateException("submission transaction returned null");
        if (work.queue()) queueGrading(work.attempt().getId());
        return attemptMapper.selectById(work.attempt().getId());
    }

    public AssessmentAttemptEntity retryGrading(String attemptId) {
        AssessmentAttemptEntity attempt = ownedAttempt(attemptId);
        if (!Set.of("PARTIALLY_GRADED", "GRADING_FAILED").contains(attempt.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "当前评估不需要重新批改");
        }
        attempt.setStatus("SUBMITTED");
        attempt.setInvalidReason(null);
        attemptMapper.updateById(attempt);
        queueGrading(attempt.getId());
        return attemptMapper.selectById(attempt.getId());
    }

    private record SubmitWork(AssessmentAttemptEntity attempt, boolean queue) {}

    private void queueGrading(long attemptId) {
        try {
            aiBackgroundExecutor.execute(() -> {
                AssessmentAttemptEntity attempt = attemptMapper.selectById(attemptId);
                if (attempt == null || !"SUBMITTED".equals(attempt.getStatus())) return;
                try {
                    grade(attempt);
                } catch (Exception error) {
                    attempt.setStatus("GRADING_FAILED");
                    attempt.setInvalidReason(error instanceof AiModelException modelError
                            ? modelError.getCode().name() : "ASSESSMENT_GRADING_FAILED");
                    attemptMapper.updateById(attempt);
                }
            });
        } catch (RuntimeException rejected) {
            AssessmentAttemptEntity attempt = attemptMapper.selectById(attemptId);
            if (attempt != null) {
                attempt.setStatus("GRADING_FAILED");
                attempt.setInvalidReason("SERVICE_TEMPORARILY_UNAVAILABLE");
                attemptMapper.updateById(attempt);
            }
        }
    }

    private void grade(AssessmentAttemptEntity at) {
        at.setStatus("GRADING");
        int gradingVersion = at.getGradingVersion() + 1;
        at.setGradingVersion(gradingVersion);
        attemptMapper.updateById(at);
        AssessmentEntity assessment = assessmentMapper.selectById(at.getAssessmentId());
        List<AssessmentQuestionEntity> questions = assessmentQuestionMapper.selectList(new LambdaQueryWrapper<AssessmentQuestionEntity>().eq(AssessmentQuestionEntity::getAssessmentId, assessment.getId()));
        BigDecimal total = BigDecimal.ZERO;
        boolean pending = false;
        for (AssessmentQuestionEntity aq : questions) {
            QuestionVersionEntity qv = questionVersionMapper.selectById(aq.getQuestionVersionId());
            AttemptAnswerEntity answer = answerMapper.selectOne(new LambdaQueryWrapper<AttemptAnswerEntity>().eq(AttemptAnswerEntity::getAttemptId, at.getId()).eq(AttemptAnswerEntity::getSequenceNo, aq.getSequenceNo()));
            if (answer == null) {
                answer = new AttemptAnswerEntity();
                answer.setPublicId(UUID.randomUUID().toString());
                answer.setAttemptId(at.getId());
                answer.setSequenceNo(aq.getSequenceNo());
                answer.setAnswerJson("null");
                answer.setSavedAt(Instant.now());
                answer.setGradingStatus("PENDING");
                answerMapper.insert(answer);
            }
            if ("GRADED".equals(answer.getGradingStatus()) && answer.getScore() != null) {
                total = total.add(answer.getScore());
                continue;
            }
            if (Set.of("SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE", "FILL_BLANK").contains(qv.getType())) {
                boolean correct = jsonEqual(answer.getAnswerJson(), qv.getAnswerJson());
                BigDecimal score = correct ? aq.getScore() : BigDecimal.ZERO;
                answer.setScore(score);
                answer.setGradingStatus("GRADED");
                answer.setGraderType("RULE");
                answer.setGraderConfidence(BigDecimal.ONE);
                answer.setFeedback(correct ? "回答正确" : qv.getAnalysis());
                answerMapper.updateById(answer);
                recordGrading(answer, gradingVersion, qv.getRubricJson(), score, BigDecimal.ONE, "RULE", answer.getFeedback());
                total = total.add(score);
                recordEvidence(at, assessment, aq, qv, answer, score);
                if (!correct) recordWrong(at, qv, answer);
            } else {
                if (!pythonAi.isConfigured()) {
                    answer.setGradingStatus("PENDING_REVIEW");
                    answer.setFeedback("主观题等待模型服务恢复后批改");
                    answerMapper.updateById(answer);
                    pending = true;
                    continue;
                }
                SubjectiveGrading graded;
                try {
                    graded = gradeSubjective(qv, answer, aq.getScore());
                } catch (AiModelException error) {
                    answer.setGradingStatus("PENDING_REVIEW");
                    answer.setFeedback("主观题批改暂时失败，可稍后重试");
                    answerMapper.updateById(answer);
                    pending = true;
                    continue;
                }
                answer.setScore(graded.score());
                answer.setGradingStatus("GRADED");
                answer.setGraderType("AI");
                answer.setGraderConfidence(graded.confidence());
                answer.setFeedback(graded.feedback());
                answerMapper.updateById(answer);
                recordGrading(answer, gradingVersion, qv.getRubricJson(), graded.score(), graded.confidence(), "AI", graded.feedback());
                total = total.add(graded.score());
                recordEvidence(at, assessment, aq, qv, answer, graded.score());
            }
        }
        at.setTotalScore(total);
        at.setStatus(pending ? "PARTIALLY_GRADED" : "GRADED");
        attemptMapper.updateById(at);
        recalculateAll(at.getUserId());
        snapshotMastery(at.getUserId());
        emitFeedbackEvent(at.getUserId(), at.getPublicId(), "ASSESSMENT_GRADED");
    }

    private void recordGrading(AttemptAnswerEntity answer, int version, String rubric, BigDecimal score,
                               BigDecimal confidence, String graderType, String reason) {
        String safeReason = reason == null ? null : reason.substring(0, Math.min(2000, reason.length()));
        jdbc.update("""
                INSERT INTO grading_record
                (id,answer_id,grading_version,rubric_snapshot_json,score,confidence,grader_type,model_run_id,reason,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, IdWorker.getId(), answer.getId(), version, rubric, score, confidence,
                graderType, null, safeReason, Instant.now());
    }

    private void snapshotMastery(long userId) {
        List<Map<String, Object>> values = jdbc.query("""
                SELECT km.knowledge_point_id,kp.name,km.score,km.confidence,km.level,km.evidence_count,km.calculated_at
                FROM knowledge_mastery km
                JOIN knowledge_point kp ON kp.id=km.knowledge_point_id
                WHERE km.user_id=? ORDER BY km.knowledge_point_id
                """, (rs, row) -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("knowledgePointId", String.valueOf(rs.getLong(1)));
            value.put("name", rs.getString(2));
            value.put("score", rs.getBigDecimal(3));
            value.put("confidence", rs.getBigDecimal(4));
            value.put("level", rs.getString(5));
            value.put("evidenceCount", rs.getInt(6));
            value.put("calculatedAt", rs.getTimestamp(7).toInstant());
            return value;
        }, userId);
        jdbc.update("""
                INSERT INTO mastery_snapshot(id,user_id,scope_type,scope_id,snapshot_at,data_json,calc_version)
                VALUES(?,?,?,?,?,?,?)
                """, IdWorker.getId(), userId, "ALL", null, Instant.now(), toJson(values), "1.0");
    }

    private void recordEvidence(AssessmentAttemptEntity at, AssessmentEntity assessment, AssessmentQuestionEntity aq, QuestionVersionEntity qv, AttemptAnswerEntity answer, BigDecimal score) {
        List<Map<String, Object>> alloc = jdbc.query("SELECT knowledge_point_id,allocation FROM question_knowledge_point WHERE question_version_id=?", (rs, row) -> Map.of("kp", rs.getLong(1), "allocation", rs.getBigDecimal(2)), qv.getId());
        double difficulty = switch (qv.getDifficulty()) {
            case 1 -> .8;
            case 2 -> .9;
            case 4 -> 1.1;
            case 5 -> 1.2;
            default -> 1.0;
        };
        for (var x : alloc) {
            MasteryEvidenceEntity e = new MasteryEvidenceEntity();
            e.setUserId(at.getUserId());
            e.setKnowledgePointId((Long) x.get("kp"));
            e.setEvidenceType("ASSESSMENT_" + assessment.getType());
            e.setSourceId(answer.getId());
            e.setScore(score.divide(aq.getScore(), 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)));
            e.setWeight(BigDecimal.valueOf(difficulty).multiply((BigDecimal) x.get("allocation")));
            e.setOccurredAt(at.getSubmittedAt());
            e.setValidFlag(true);
            e.setCalcVersion("1.0");
            e.setCreatedAt(Instant.now());
            evidenceMapper.insert(e);
        }
    }

    private void recordWrong(AssessmentAttemptEntity at, QuestionVersionEntity qv, AttemptAnswerEntity answer) {
        String reasonCode = "KNOWLEDGE_GAP";
        if (pythonAi.isConfigured()) {
            try {
                reasonCode = explainWrong(qv, answer);
            } catch (AiModelException ignored) {
                // 错因分析失败不能回滚已经完成的客观题批改。
            }
        }
        List<Long> kps = jdbc.query("SELECT knowledge_point_id FROM question_knowledge_point WHERE question_version_id=?", (rs, row) -> rs.getLong(1), qv.getId());
        for (Long kp : kps) {
            WrongQuestionEntity w = wrongMapper.selectOne(new LambdaQueryWrapper<WrongQuestionEntity>().eq(WrongQuestionEntity::getUserId, at.getUserId()).eq(WrongQuestionEntity::getQuestionVersionId, qv.getId()).eq(WrongQuestionEntity::getKnowledgePointId, kp));
            if (w == null) {
                w = new WrongQuestionEntity();
                w.setUserId(at.getUserId());
                w.setQuestionVersionId(qv.getId());
                w.setKnowledgePointId(kp);
                w.setFirstWrongAt(Instant.now());
                w.setWrongCount(1);
            } else w.setWrongCount(w.getWrongCount() + 1);
            w.setLastWrongAt(Instant.now());
            w.setAiReasonCode(reasonCode);
            w.setStatus("OPEN");
            w.setCorrectedAt(null);
            if (w.getId() == null) wrongMapper.insert(w);
            else wrongMapper.updateById(w);
        }
    }

    private record SubjectiveGrading(BigDecimal score, BigDecimal confidence, String feedback) {}

    private SubjectiveGrading gradeSubjective(QuestionVersionEntity qv, AttemptAnswerEntity answer, BigDecimal maxScore) {
        String userPrompt = "题目：" + qv.getStem() + "\n"
                + "参考答案：" + qv.getAnswerJson() + "\n"
                + "评分标准：" + (qv.getRubricJson() == null ? "无" : qv.getRubricJson()) + "\n"
                + "学生答案：" + answer.getAnswerJson() + "\n"
                + "满分：" + maxScore + "\n"
                + "请评分并输出 JSON：{\"score\":0到满分的数值,\"confidence\":0到1的置信度,\"feedback\":\"中文反馈\"}";
        AiModelClient.Completion result = pythonAi.complete(
                "你是学习评估助手。根据题目、参考答案和评分标准，对学生答案评分。只输出 JSON，不要 Markdown 或解释。",
                userPrompt);
        try {
            var root = json.readTree(result.content().trim());
            BigDecimal score = root.path("score").isNumber() ? root.path("score").decimalValue() : BigDecimal.ZERO;
            if (score.compareTo(BigDecimal.ZERO) < 0) score = BigDecimal.ZERO;
            if (score.compareTo(maxScore) > 0) score = maxScore;
            BigDecimal confidence = root.path("confidence").isNumber() ? root.path("confidence").decimalValue() : new BigDecimal("0.5");
            if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) confidence = new BigDecimal("0.5");
            String feedback = root.path("feedback").isTextual() ? root.path("feedback").asText().trim() : "已评分";
            if (feedback.length() > 500) feedback = feedback.substring(0, 500);
            return new SubjectiveGrading(score, confidence, feedback);
        } catch (Exception e) {
            throw new AiModelException(ErrorCode.MODEL_PROVIDER_ERROR, e);
        }
    }

    private String explainWrong(QuestionVersionEntity qv, AttemptAnswerEntity answer) {
        String userPrompt = "题目：" + qv.getStem() + "\n"
                + "正确答案：" + qv.getAnswerJson() + "\n"
                + "学生答案：" + answer.getAnswerJson() + "\n"
                + "解析：" + (qv.getAnalysis() == null ? "无" : qv.getAnalysis()) + "\n"
                + "请分析错误原因，只输出一个归因码：CONCEPT_UNCLEAR/CARELESS/METHOD_WRONG/KNOWLEDGE_GAP/MISREAD";
        AiModelClient.Completion result = pythonAi.complete(
                "你是错题分析助手。根据题目和学生答案分析错误原因，只输出一个归因码，不要其他文字。",
                userPrompt);
        String content = result.content().trim().toUpperCase();
        if (!Set.of("CONCEPT_UNCLEAR", "CARELESS", "METHOD_WRONG", "KNOWLEDGE_GAP", "MISREAD").contains(content))
            throw new AiModelException(ErrorCode.MODEL_PROVIDER_ERROR);
        return content;
    }

    public AttemptResult result(String attemptId) {
        AssessmentAttemptEntity at = ownedAttempt(attemptId);
        // 未交卷也允许读取，便于前端「继续答题」加载已保存的草稿答案；评分字段此时为空
        List<Map<String, Object>> answers = jdbc.query("""
            SELECT aa.public_id,aa.sequence_no,aa.answer_json,aa.score,aa.feedback,aa.grading_status,
                   aq.score max_score,aq.snapshot_json,qv.answer_json,qv.analysis
            FROM attempt_answer aa JOIN assessment_question aq ON aq.assessment_id=? AND aq.sequence_no=aa.sequence_no JOIN question_version qv ON qv.id=aq.question_version_id WHERE aa.attempt_id=? ORDER BY aa.sequence_no
            """, (rs, row) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("answerId", rs.getString(1));
            m.put("sequence", rs.getInt(2));
            m.put("answer", read(rs.getString(3), new TypeReference<Object>() {
            }));
            m.put("score", rs.getBigDecimal(4));
            m.put("feedback", rs.getString(5));
            m.put("gradingStatus", rs.getString(6));
            m.put("maxScore", rs.getBigDecimal(7));
            m.put("question", read(rs.getString(8), new TypeReference<Object>() {
            }));
            m.put("standardAnswer", read(rs.getString(9), new TypeReference<Object>() {
            }));
            m.put("analysis", rs.getString(10));
            return m;
        }, at.getAssessmentId(), at.getId());
        for (Map<String, Object> answer : answers) {
            answer.put("gradingHistory", jdbc.query("""
                    SELECT gr.grading_version,gr.score,gr.confidence,gr.grader_type,gr.reason,gr.created_at
                    FROM grading_record gr
                    JOIN attempt_answer aa ON aa.id=gr.answer_id
                    WHERE aa.public_id=? ORDER BY gr.grading_version DESC
                    """, (rs, row) -> {
                Map<String, Object> grading = new LinkedHashMap<>();
                grading.put("version", rs.getInt(1));
                grading.put("score", rs.getBigDecimal(2));
                grading.put("confidence", rs.getBigDecimal(3));
                grading.put("graderType", rs.getString(4));
                grading.put("reason", rs.getString(5));
                grading.put("createdAt", rs.getTimestamp(6).toInstant());
                return grading;
            }, answer.get("answerId")));
        }
        return new AttemptResult(at, answers);
    }

    public List<WrongQuestionEntity> wrongQuestions() {
        return wrongMapper.selectList(new LambdaQueryWrapper<WrongQuestionEntity>().eq(WrongQuestionEntity::getUserId, SecurityUtils.currentUserId()).orderByDesc(WrongQuestionEntity::getLastWrongAt));
    }

    @Transactional
    public WrongQuestionEntity correctWrong(long id,String reasonCode) {
        WrongQuestionEntity wrong=wrongMapper.selectOne(new LambdaQueryWrapper<WrongQuestionEntity>()
                .eq(WrongQuestionEntity::getId,id).eq(WrongQuestionEntity::getUserId,SecurityUtils.currentUserId()));
        if(wrong==null)notFound();
        wrong.setConfirmedReasonCode(reasonCode);
        wrong.setStatus("RESOLVED");
        wrong.setCorrectedAt(Instant.now());
        wrongMapper.updateById(wrong);
        return wrong;
    }

    public List<KnowledgeMasteryEntity> mastery() {
        return masteryMapper.selectList(new LambdaQueryWrapper<KnowledgeMasteryEntity>().eq(KnowledgeMasteryEntity::getUserId, SecurityUtils.currentUserId()).orderByAsc(KnowledgeMasteryEntity::getKnowledgePointId));
    }

    public void appeal(String answerId, String reason, Object evidence) {
        AttemptAnswerEntity a = answerMapper.selectOne(new LambdaQueryWrapper<AttemptAnswerEntity>().eq(AttemptAnswerEntity::getPublicId, answerId));
        if (a == null) notFound();
        AssessmentAttemptEntity at = attemptMapper.selectById(a.getAttemptId());
        if (at == null || !at.getUserId().equals(SecurityUtils.currentUserId())) notFound();
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM assessment_appeal WHERE answer_id=? AND user_id=? AND status='PENDING'", Integer.class, a.getId(), at.getUserId());
        if (exists != null && exists > 0) return;
        jdbc.update("INSERT INTO assessment_appeal(id,public_id,answer_id,user_id,reason,evidence_json,status,created_at) VALUES(?,?,?,?,?,?,?,?)", IdWorker.getId(), UUID.randomUUID().toString(), a.getId(), at.getUserId(), reason, toJson(evidence), "PENDING", Instant.now());
    }

    public List<Map<String,Object>> appeals() {
        return appealRows("WHERE appeal.user_id=?", SecurityUtils.currentUserId());
    }

    public List<Map<String,Object>> adminAppeals(String status) {
        return status==null||status.isBlank()?appealRows("WHERE 1=1"):
                appealRows("WHERE appeal.status=?",status.toUpperCase(Locale.ROOT));
    }

    @Transactional
    public void withdrawAppeal(String publicId) {
        int updated=jdbc.update("UPDATE assessment_appeal SET status='WITHDRAWN',resolved_at=? WHERE public_id=? AND user_id=? AND status='PENDING'",
                Instant.now(),publicId,SecurityUtils.currentUserId());
        if(updated!=1)throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,"申诉不存在或已处理");
    }

    @Transactional
    public void resolveAppeal(String publicId,boolean accepted,String resolution,BigDecimal correctedScore) {
        List<Map<String,Object>> rows=jdbc.queryForList("""
                SELECT appeal.id,appeal.answer_id,appeal.user_id,answer.attempt_id,answer.score,
                       aq.score max_score,qv.rubric_json
                FROM assessment_appeal appeal
                JOIN attempt_answer answer ON answer.id=appeal.answer_id
                JOIN assessment_attempt attempt ON attempt.id=answer.attempt_id
                JOIN assessment_question aq ON aq.assessment_id=attempt.assessment_id AND aq.sequence_no=answer.sequence_no
                JOIN question_version qv ON qv.id=aq.question_version_id
                WHERE appeal.public_id=? AND appeal.status='PENDING'
                """,publicId);
        if(rows.isEmpty())notFound();
        Map<String,Object> row=rows.get(0);
        long answerId=((Number)row.get("answer_id")).longValue();
        long attemptId=((Number)row.get("attempt_id")).longValue();
        long userId=((Number)row.get("user_id")).longValue();
        BigDecimal maxScore=(BigDecimal)row.get("max_score");
        BigDecimal score=correctedScore==null?(BigDecimal)row.get("score"):correctedScore;
        if(accepted&&(score==null||score.signum()<0||score.compareTo(maxScore)>0))
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,"复核分数超出合法范围");
        jdbc.update("UPDATE assessment_appeal SET status=?,resolution=?,resolved_at=? WHERE public_id=? AND status='PENDING'",
                accepted?"ACCEPTED":"REJECTED",resolution,Instant.now(),publicId);
        if(!accepted)return;
        jdbc.update("UPDATE attempt_answer SET score=?,grading_status='GRADED',grader_type='HUMAN',grader_confidence=1,feedback=? WHERE id=?",
                score,resolution,answerId);
        Integer version=jdbc.queryForObject("SELECT COALESCE(MAX(grading_version),0)+1 FROM grading_record WHERE answer_id=?",Integer.class,answerId);
        jdbc.update("INSERT INTO grading_record(id,answer_id,grading_version,rubric_snapshot_json,score,confidence,grader_type,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                IdWorker.getId(),answerId,version,row.get("rubric_json"),score,BigDecimal.ONE,"HUMAN",resolution,Instant.now());
        BigDecimal normalized=score.divide(maxScore,6,RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        jdbc.update("UPDATE mastery_evidence SET score=? WHERE user_id=? AND source_id=? AND valid_flag=1",normalized,userId,answerId);
        jdbc.update("UPDATE assessment_attempt SET total_score=(SELECT COALESCE(SUM(score),0) FROM attempt_answer WHERE attempt_id=?) WHERE id=?",attemptId,attemptId);
        recalculateAll(userId);
        snapshotMastery(userId);
        emitFeedbackEvent(userId,publicId,"APPEAL_RESOLVED");
    }

    private List<Map<String,Object>> appealRows(String where,Object...args) {
        return jdbc.query("""
                SELECT appeal.public_id,appeal.status,appeal.reason,appeal.resolution,appeal.created_at,
                       appeal.resolved_at,answer.public_id answer_id,answer.score,u.username
                FROM assessment_appeal appeal
                JOIN attempt_answer answer ON answer.id=appeal.answer_id
                JOIN sys_user u ON u.id=appeal.user_id
                """+where+" ORDER BY appeal.created_at DESC",(rs,row)->{
            Map<String,Object> value=new LinkedHashMap<>();
            value.put("publicId",rs.getString("public_id"));value.put("status",rs.getString("status"));
            value.put("reason",rs.getString("reason"));value.put("resolution",rs.getString("resolution"));
            value.put("createdAt",rs.getTimestamp("created_at").toInstant());
            value.put("resolvedAt",rs.getTimestamp("resolved_at")==null?null:rs.getTimestamp("resolved_at").toInstant());
            value.put("answerId",rs.getString("answer_id"));value.put("score",rs.getBigDecimal("score"));
            value.put("username",rs.getString("username"));return value;
        },args);
    }

    @Transactional
    public void recordLearningBlockEvidence(long userId, long taskId, long attemptId,
                                            BigDecimal score, Instant occurredAt) {
        List<Map<String, Object>> points = jdbc.query("""
                SELECT knowledge_point_id,weight
                FROM task_knowledge_point
                WHERE task_id=?
                """, (rs, row) -> Map.of("id", rs.getLong(1), "weight", rs.getBigDecimal(2)), taskId);
        for (Map<String, Object> point : points) {
            long knowledgePointId = ((Number) point.get("id")).longValue();
            upsertEvidence(userId, knowledgePointId, "LEARNING_BLOCK_TEST", attemptId, score,
                    (BigDecimal) point.get("weight"), occurredAt);
            recalculate(userId, knowledgePointId);
        }
    }

    @Transactional
    public void recordProjectMilestoneEvidence(long userId, long projectId, long milestoneId,
                                               BigDecimal milestoneWeight, Instant occurredAt) {
        List<Map<String, Object>> points = jdbc.query("""
                SELECT tkp.knowledge_point_id,MAX(tkp.weight) evidence_weight
                FROM learning_task task
                JOIN task_knowledge_point tkp ON tkp.task_id=task.id
                WHERE task.user_id=? AND task.deleted_at IS NULL
                  AND (task.project_id=? OR EXISTS (
                    SELECT 1 FROM goal_project gp
                    WHERE gp.project_id=? AND gp.goal_id=task.goal_id
                  ))
                GROUP BY tkp.knowledge_point_id
                """, (rs, row) -> Map.of("id", rs.getLong(1), "weight", rs.getBigDecimal(2)),
                userId, projectId, projectId);
        BigDecimal contribution = milestoneWeight == null ? BigDecimal.ONE : milestoneWeight;
        for (Map<String, Object> point : points) {
            long knowledgePointId = ((Number) point.get("id")).longValue();
            BigDecimal weight = ((BigDecimal) point.get("weight")).multiply(contribution)
                    .max(new BigDecimal("0.05"));
            upsertEvidence(userId, knowledgePointId, "PROJECT_MILESTONE", milestoneId,
                    BigDecimal.valueOf(100), weight, occurredAt);
            recalculate(userId, knowledgePointId);
        }
    }

    private void upsertEvidence(long userId, long knowledgePointId, String type, long sourceId,
                                BigDecimal score, BigDecimal weight, Instant occurredAt) {
        MasteryEvidenceEntity evidence = evidenceMapper.selectOne(new LambdaQueryWrapper<MasteryEvidenceEntity>()
                .eq(MasteryEvidenceEntity::getEvidenceType, type)
                .eq(MasteryEvidenceEntity::getSourceId, sourceId)
                .eq(MasteryEvidenceEntity::getKnowledgePointId, knowledgePointId));
        if (evidence == null) {
            evidence = new MasteryEvidenceEntity();
            evidence.setUserId(userId);
            evidence.setKnowledgePointId(knowledgePointId);
            evidence.setEvidenceType(type);
            evidence.setSourceId(sourceId);
            evidence.setValidFlag(true);
            evidence.setCalcVersion("1.0");
            evidence.setCreatedAt(Instant.now());
        }
        evidence.setScore(score.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100)));
        evidence.setWeight(weight == null ? BigDecimal.ONE : weight);
        evidence.setOccurredAt(occurredAt == null ? Instant.now() : occurredAt);
        if (evidence.getId() == null) evidenceMapper.insert(evidence);
        else evidenceMapper.updateById(evidence);
    }

    private void emitFeedbackEvent(long userId,String aggregateId,String eventType) {
        jdbc.update("INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,payload_json,correlation_id,status,attempts,next_retry_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                IdWorker.getId(),"LEARNING_FEEDBACK",aggregateId,eventType,toJson(Map.of("userId",userId)),
                UUID.randomUUID().toString(),"PENDING",0,Instant.now(),Instant.now());
    }

    private void recalculateAll(long user) {
        List<Long> kps = jdbc.query("SELECT DISTINCT knowledge_point_id FROM mastery_evidence WHERE user_id=? AND valid_flag=1", (rs, row) -> rs.getLong(1), user);
        for (Long kp : kps) recalculate(user, kp);
    }

    private void recalculate(long user, long kp) {
        List<MasteryPolicy.Evidence> ev = new ArrayList<>();
        for (MasteryEvidenceEntity e : evidenceMapper.selectList(new LambdaQueryWrapper<MasteryEvidenceEntity>().eq(MasteryEvidenceEntity::getUserId, user).eq(MasteryEvidenceEntity::getKnowledgePointId, kp).eq(MasteryEvidenceEntity::getValidFlag, true))) {
            double type = e.getEvidenceType().contains("STAGE") ? 1.2 : e.getEvidenceType().contains("DIAGNOSTIC") || e.getEvidenceType().contains("PRACTICE") ? .8 : 1;
            ev.add(new MasteryPolicy.Evidence(MasteryPolicy.Component.Q, e.getScore().doubleValue(), e.getWeight().doubleValue() * type, e.getOccurredAt(), true));
        }
        List<Map<String, Object>> self = jdbc.query("SELECT level,assessed_at FROM self_assessment WHERE user_id=? AND knowledge_point_id=? AND deleted_at IS NULL AND assessed_at>=? ORDER BY assessed_at DESC LIMIT 1", (rs, row) -> Map.of("level", rs.getInt(1), "at", rs.getTimestamp(2).toInstant()), user, kp, Instant.now().minus(Duration.ofDays(30)));
        if (!self.isEmpty())
            ev.add(new MasteryPolicy.Evidence(MasteryPolicy.Component.S, ((Integer) self.get(0).get("level")) * 20, .1, (Instant) self.get(0).get("at"), false));
        if (ev.isEmpty()) return;
        MasteryPolicy.Result r = MasteryPolicy.calculate(ev, Instant.now());
        KnowledgeMasteryEntity m = masteryMapper.selectOne(new LambdaQueryWrapper<KnowledgeMasteryEntity>().eq(KnowledgeMasteryEntity::getUserId, user).eq(KnowledgeMasteryEntity::getKnowledgePointId, kp));
        if (m == null) {
            m = new KnowledgeMasteryEntity();
            m.setUserId(user);
            m.setKnowledgePointId(kp);
        }
        m.setScore(BigDecimal.valueOf(r.score()));
        m.setConfidence(BigDecimal.valueOf(r.confidence()));
        m.setLevel(r.level());
        m.setEvidenceCount(r.evidenceCount());
        m.setCalculatedAt(Instant.now());
        m.setCalcVersion("1.0");
        if (m.getId() == null) masteryMapper.insert(m);
        else masteryMapper.updateById(m);
    }

    private void validateQuestion(QuestionInput i) {
        if (!QUESTION_TYPES.contains(i.type()) || i.stem() == null || i.stem().isBlank() || i.stem().length() > 4000 || i.difficulty() < 1 || i.difficulty() > 5 || i.knowledgePointIds() == null || i.knowledgePointIds().isEmpty() || i.answer() == null)
            bad("题目字段不完整");
        if (Set.of("SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE").contains(i.type()) && (i.options() == null || i.options().size() < 2))
            bad("客观题选项不完整");
    }

    private void apply(QuestionVersionEntity v, QuestionInput i) {
        v.setType(i.type());
        v.setStem(i.stem());
        v.setOptionsJson(toJson(i.options()));
        v.setAnswerJson(toJson(i.answer()));
        v.setRubricJson(toJson(i.rubric()));
        v.setAnalysis(i.analysis());
        v.setDifficulty(i.difficulty());
        v.setCreatedAt(Instant.now());
    }

    private QuestionEntity accessibleQuestion(String id) {
        QuestionEntity q = questionMapper.selectOne(new LambdaQueryWrapper<QuestionEntity>().eq(QuestionEntity::getPublicId, id).and(x -> x.eq(QuestionEntity::getVisibility, "PUBLIC").eq(QuestionEntity::getStatus, "PUBLISHED").or().eq(QuestionEntity::getOwnerUserId, SecurityUtils.currentUserId())));
        if (q == null) notFound();
        return q;
    }

    private QuestionVersionEntity currentVersion(QuestionEntity q) {
        return questionVersionMapper.selectOne(new LambdaQueryWrapper<QuestionVersionEntity>().eq(QuestionVersionEntity::getQuestionId, q.getId()).eq(QuestionVersionEntity::getVersionNo, q.getCurrentVersionNo()));
    }

    private AssessmentEntity accessibleAssessment(String id) {
        AssessmentEntity a = assessmentMapper.selectOne(new LambdaQueryWrapper<AssessmentEntity>().eq(AssessmentEntity::getPublicId, id).eq(AssessmentEntity::getStatus, "PUBLISHED").and(x -> x.isNull(AssessmentEntity::getOwnerUserId).or().eq(AssessmentEntity::getOwnerUserId, SecurityUtils.currentUserId())));
        if (a == null) notFound();
        return a;
    }

    private AssessmentAttemptEntity ownedAttempt(String id) {
        AssessmentAttemptEntity a = attemptMapper.selectOne(new LambdaQueryWrapper<AssessmentAttemptEntity>().eq(AssessmentAttemptEntity::getPublicId, id).eq(AssessmentAttemptEntity::getUserId, SecurityUtils.currentUserId()));
        if (a == null) notFound();
        return a;
    }

    private boolean jsonEqual(String a, String b) {
        try {
            return json.readTree(a).equals(json.readTree(b));
        } catch (Exception e) {
            return false;
        }
    }

    private <T> T read(String v, TypeReference<T> t) {
        if (v == null) return null;
        try {
            return json.readValue(v, t);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void bad(String m) {
        throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, m);
    }

    private void notFound() {
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "资源不存在");
    }
}

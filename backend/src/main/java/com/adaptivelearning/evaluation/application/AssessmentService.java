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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.*;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AssessmentService {
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
        List<Map<String, Object>> rows = jdbc.query("""
            SELECT DISTINCT q.public_id FROM question q JOIN question_version v ON v.question_id=q.id AND v.version_no=q.current_version_no
            JOIN question_knowledge_point qk ON qk.question_version_id=v.id JOIN knowledge_point kp ON kp.id=qk.knowledge_point_id
            WHERE q.status='PUBLISHED' AND q.visibility='PUBLIC' AND kp.direction_id=? AND v.difficulty BETWEEN ? AND ? ORDER BY v.difficulty LIMIT ?
            """, (rs, row) -> Map.of("id", rs.getString(1)), directionId, Math.max(1, difficulty - 1), Math.min(5, difficulty + 1), Math.max(1, durationMinutes / 3));
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "该方向暂无可用诊断题");
        List<QuestionRef> refs = rows.stream().map(r -> new QuestionRef((String) r.get("id"), BigDecimal.TEN)).toList();
        return create(new AssessmentInput("DIAGNOSTIC", "能力诊断", durationMinutes, 1, BigDecimal.valueOf(refs.size() * 6L), refs));
    }

    public PageResponse<AssessmentEntity> list(int page, int pageSize) {
        long user = SecurityUtils.currentUserId();
        var q = new LambdaQueryWrapper<AssessmentEntity>().and(x -> x.eq(AssessmentEntity::getOwnerUserId, user).or().isNull(AssessmentEntity::getOwnerUserId)).eq(AssessmentEntity::getStatus, "PUBLISHED").orderByDesc(AssessmentEntity::getCreatedAt);
        Page<AssessmentEntity> p = assessmentMapper.selectPage(Page.of(page, Math.min(100, pageSize)), q);
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

    @Transactional
    public AssessmentAttemptEntity submit(String attemptId, String key) {
        if (key == null || key.isBlank()) bad("缺少 Idempotency-Key");
        long user = SecurityUtils.currentUserId();
        IdempotencyRecordEntity old = idempotency.find(user, key, attemptId);
        if (old != null) return ownedAttempt(old.getResponseRef());
        AssessmentAttemptEntity probe = ownedAttempt(attemptId);
        AssessmentAttemptEntity at = attemptMapper.lock(probe.getId());
        if (!"IN_PROGRESS".equals(at.getStatus())) return at;
        at.setStatus("SUBMITTED");
        at.setSubmittedAt(Instant.now());
        attemptMapper.updateById(at);
        grade(at);
        idempotency.save(user, key, attemptId, at.getPublicId());
        return attemptMapper.selectById(at.getId());
    }

    private void grade(AssessmentAttemptEntity at) {
        at.setStatus("GRADING");
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
            if (Set.of("SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE", "FILL_BLANK").contains(qv.getType())) {
                boolean correct = jsonEqual(answer.getAnswerJson(), qv.getAnswerJson());
                BigDecimal score = correct ? aq.getScore() : BigDecimal.ZERO;
                answer.setScore(score);
                answer.setGradingStatus("GRADED");
                answer.setGraderType("RULE");
                answer.setGraderConfidence(BigDecimal.ONE);
                answer.setFeedback(correct ? "回答正确" : qv.getAnalysis());
                answerMapper.updateById(answer);
                total = total.add(score);
                recordEvidence(at, assessment, aq, qv, answer, score);
                if (!correct) recordWrong(at, qv, answer);
            } else {
                if (!pythonAi.isConfigured()) throw new AiModelException(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE);
                SubjectiveGrading graded = gradeSubjective(qv, answer, aq.getScore());
                answer.setScore(graded.score());
                answer.setGradingStatus("GRADED");
                answer.setGraderType("AI");
                answer.setGraderConfidence(graded.confidence());
                answer.setFeedback(graded.feedback());
                answerMapper.updateById(answer);
                total = total.add(graded.score());
                recordEvidence(at, assessment, aq, qv, answer, graded.score());
            }
        }
        at.setTotalScore(total);
        at.setGradingVersion(at.getGradingVersion() + 1);
        at.setStatus(pending ? "PARTIALLY_GRADED" : "GRADED");
        attemptMapper.updateById(at);
        recalculateAll(at.getUserId());
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
        if (!pythonAi.isConfigured()) throw new AiModelException(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE);
        String reasonCode = explainWrong(qv, answer);
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
        if ("IN_PROGRESS".equals(at.getStatus()))
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "评估尚未提交");
        List<Map<String, Object>> answers = jdbc.query("""
            SELECT aa.public_id,aa.sequence_no,aa.answer_json,aa.score,aa.feedback,aq.score max_score,aq.snapshot_json,qv.answer_json,qv.analysis
            FROM attempt_answer aa JOIN assessment_question aq ON aq.assessment_id=? AND aq.sequence_no=aa.sequence_no JOIN question_version qv ON qv.id=aq.question_version_id WHERE aa.attempt_id=? ORDER BY aa.sequence_no
            """, (rs, row) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("answerId", rs.getString(1));
            m.put("sequence", rs.getInt(2));
            m.put("answer", read(rs.getString(3), new TypeReference<Object>() {
            }));
            m.put("score", rs.getBigDecimal(4));
            m.put("feedback", rs.getString(5));
            m.put("maxScore", rs.getBigDecimal(6));
            m.put("question", read(rs.getString(7), new TypeReference<Object>() {
            }));
            m.put("standardAnswer", read(rs.getString(8), new TypeReference<Object>() {
            }));
            m.put("analysis", rs.getString(9));
            return m;
        }, at.getAssessmentId(), at.getId());
        return new AttemptResult(at, answers);
    }

    public List<WrongQuestionEntity> wrongQuestions() {
        return wrongMapper.selectList(new LambdaQueryWrapper<WrongQuestionEntity>().eq(WrongQuestionEntity::getUserId, SecurityUtils.currentUserId()).orderByDesc(WrongQuestionEntity::getLastWrongAt));
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
        if (i.stem() == null || i.stem().isBlank() || i.stem().length() > 4000 || i.difficulty() < 1 || i.difficulty() > 5 || i.knowledgePointIds() == null || i.knowledgePointIds().isEmpty() || i.answer() == null)
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

package com.adaptivelearning.execution.application;

import com.adaptivelearning.evaluation.application.AssessmentService;
import com.adaptivelearning.shared.ai.PythonAiServiceClient;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.adaptivelearning.shared.web.RequestIdFilter;
import com.adaptivelearning.support.application.AuditService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executor;

@Service
@RequiredArgsConstructor
public class LearningBlockService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PythonAiServiceClient pythonAi;
    private final AuditService audit;
    private final AssessmentService assessments;
    @Autowired @Qualifier("aiBackgroundExecutor")
    private Executor aiBackgroundExecutor;

    public Map<String, Object> byTask(String taskPublicId) {
        Map<String, Object> block = ownedByTask(taskPublicId);
        return view(block);
    }

    @Transactional
    public Map<String, Object> attachSources(String taskPublicId, List<String> requestedSpaceIds) {
        Map<String, Object> block = ownedByTask(taskPublicId);
        if ("COMPLETED".equals(block.get("status")))
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "已通过的知识块不能更换资料");
        if ("GENERATING".equals(block.get("generationStatus")))
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "知识块正在生成，暂时不能更换资料");
        List<String> spaceIds = requestedSpaceIds == null ? List.of() : requestedSpaceIds.stream()
                .filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank())
                .distinct().limit(20).toList();
        if (spaceIds.isEmpty())
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "请至少选择一个已有索引资料的知识空间");
        long userId = number(block.get("userId"));
        String placeholders = String.join(",", Collections.nCopies(spaceIds.size(), "?"));
        List<Object> args = new ArrayList<>(spaceIds);
        args.add(userId);
        args.add(userId);
        List<Map<String, Object>> chunks = jdbc.query("""
                SELECT c.id,d.public_id,d.display_name,c.chunk_no,c.text,c.page_from,c.page_to
                FROM knowledge_space space
                JOIN knowledge_document d ON d.space_id=space.id
                JOIN document_version dv ON dv.document_id=d.id AND dv.version_no=d.active_version_no
                JOIN knowledge_chunk c ON c.document_version_id=dv.id
                WHERE space.public_id IN (%s) AND space.status='ACTIVE' AND space.deleted_at IS NULL
                  AND (space.user_id=? OR space.visibility='PUBLIC')
                  AND d.status='INDEXED' AND dv.status='INDEXED' AND d.deleted_at IS NULL
                  AND (d.owner_user_id=? OR d.visibility='PUBLIC')
                ORDER BY d.updated_at DESC,d.id,c.chunk_no
                LIMIT 12
                """.formatted(placeholders), (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceType", "KNOWLEDGE_CHUNK");
            item.put("title", rs.getString("display_name"));
            item.put("documentId", rs.getString("public_id"));
            item.put("chunkId", String.valueOf(rs.getLong("id")));
            item.put("chunkNo", rs.getInt("chunk_no"));
            item.put("quotePreview", abbreviate(rs.getString("text"), 500));
            item.put("pageFrom", rs.getObject("page_from"));
            item.put("pageTo", rs.getObject("page_to"));
            return item;
        }, args.toArray());
        if (chunks.isEmpty())
            throw new BusinessException(ErrorCode.RAG_EVIDENCE_INSUFFICIENT, "所选知识空间还没有已完成索引的资料");

        List<Map<String, Object>> manifest = new ArrayList<>();
        for (Map<String, Object> source : listOfMaps(block.get("sourceManifestJson"))) {
            String type = String.valueOf(source.get("sourceType"));
            if (!Set.of("UPLOAD_GUIDE", "GENERATION_NOTE", "KNOWLEDGE_CHUNK").contains(type))
                manifest.add(source);
        }
        manifest.addAll(chunks);
        long taskId = number(block.get("taskId"));
        jdbc.update("DELETE FROM task_knowledge_source WHERE task_id=?", taskId);
        for (Map<String, Object> chunk : chunks)
            jdbc.update("INSERT INTO task_knowledge_source(task_id,chunk_id,created_at) VALUES(?,?,?)",
                    taskId, chunk.get("chunkId"), Instant.now());
        jdbc.update("""
                UPDATE learning_block
                SET source_status='READY',source_manifest_json=?,generation_status='OUTLINE',
                    material_markdown=NULL,exercises_json=NULL,test_json=NULL,latest_score=NULL,
                    status='READY',updated_at=?,updated_by=?,version=version+1
                WHERE id=? AND deleted_at IS NULL
                """, toJson(manifest), Instant.now(), userId, block.get("id"));
        audit.record("LEARNING_BLOCK_SOURCE_ATTACH", "LEARNING_BLOCK",
                String.valueOf(block.get("publicId")), null, "chunks=" + chunks.size(), "SUCCESS");
        return view(ownedByTask(taskPublicId));
    }

    public Map<String, Object> summaryForTask(long taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT public_id AS publicId,sequence_no AS sequenceNo,title,objective,
                       exploration_required AS explorationRequired,source_status AS sourceStatus,
                       generation_status AS generationStatus,status,pass_score AS passScore,
                       latest_score AS latestScore,attempt_count AS attemptCount
                FROM learning_block
                WHERE task_id=? AND user_id=? AND deleted_at IS NULL
                """, taskId, SecurityUtils.currentUserId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> generate(String taskPublicId) {
        Map<String, Object> block = ownedByTask(taskPublicId);
        if ("GENERATED".equals(block.get("generationStatus"))) return view(block);
        if ("GENERATING".equals(block.get("generationStatus"))) return view(block);
        long blockId = number(block.get("id"));
        long userId = number(block.get("userId"));
        int claimed = jdbc.update("""
                UPDATE learning_block SET generation_status='GENERATING',updated_at=?,updated_by=?,version=version+1
                WHERE id=? AND generation_status IN ('OUTLINE','FAILED') AND deleted_at IS NULL
                """, Instant.now(), userId, blockId);
        if (claimed == 0) return view(ownedByTask(taskPublicId));
        String requestId = RequestIdFilter.currentRequestId();
        String clientIp = audit.currentClientIp();
        try {
            aiBackgroundExecutor.execute(() -> generateInBackground(block, requestId, clientIp));
        } catch (RuntimeException rejected) {
            failGeneration(block, requestId, clientIp, "SERVICE_TEMPORARILY_UNAVAILABLE");
        }
        return view(ownedByTask(taskPublicId));
    }

    private void generateInBackground(Map<String, Object> block, String requestId, String clientIp) {
        long blockId = number(block.get("id"));
        long userId = number(block.get("userId"));
        try {
        List<Map<String, Object>> manifest = listOfMaps(block.get("sourceManifestJson"));
        List<String> sourceQueries = stringList(block.get("sourceQueriesJson"));
        List<PythonAiServiceClient.LearningBlockSource> sources = new ArrayList<>();
        for (Map<String, Object> item : manifest) {
            String sourceType = String.valueOf(item.getOrDefault("sourceType", "UNKNOWN"));
            if ("UPLOAD_GUIDE".equals(sourceType) || "GENERATION_NOTE".equals(sourceType)) continue;
            Long chunkId = nullableLong(item.get("chunkId"));
            String quote = item.get("quotePreview") == null ? null : String.valueOf(item.get("quotePreview"));
            if (chunkId != null) {
                String fallbackQuote = quote;
                quote = jdbc.query("""
                        SELECT c.text
                        FROM knowledge_chunk c
                        JOIN task_knowledge_source source ON source.chunk_id=c.id
                        JOIN learning_task task ON task.id=source.task_id
                        WHERE c.id=? AND task.id=? AND task.user_id=?
                        """, rs -> rs.next() ? abbreviate(rs.getString(1), 3000) : fallbackQuote,
                        chunkId, block.get("taskId"), userId);
            }
            sources.add(new PythonAiServiceClient.LearningBlockSource(
                    sourceType,
                    String.valueOf(item.getOrDefault("title", "未命名资料")),
                    item.get("url") == null ? null : String.valueOf(item.get("url")),
                    quote));
        }
        PythonAiServiceClient.LearningBlockContentResult generated = pythonAi.learningBlockContent(
                new PythonAiServiceClient.LearningBlockContentRequest(
                        userId, String.valueOf(block.get("title")), String.valueOf(block.get("objective")),
                        String.valueOf(block.get("directionName")), String.valueOf(block.get("currentStage")),
                        booleanValue(block.get("explorationRequired")), sources, sourceQueries));
        jdbc.update("""
                UPDATE learning_block
                SET material_markdown=?,exercises_json=?,test_json=?,generation_status='GENERATED',
                    status=CASE WHEN status='READY' THEN 'READY' ELSE status END,
                    source_manifest_json=?,updated_at=?,updated_by=?,version=version+1
                WHERE id=? AND deleted_at IS NULL
                """, generated.materialMarkdown(), toJson(generated.exercises()),
                toJson(generated.testQuestions()), toJson(withGenerationNote(manifest, generated)),
                Instant.now(), userId, blockId);
        audit.recordAs(userId, requestId, clientIp, "LEARNING_BLOCK_GENERATE", "LEARNING_BLOCK",
                String.valueOf(block.get("publicId")), "OUTLINE",
                "GENERATED:" + generated.promptVersion(), "SUCCESS");
        } catch (Exception error) {
            String code = error instanceof com.adaptivelearning.shared.ai.AiModelException modelError
                    ? modelError.getCode().name() : "LEARNING_BLOCK_GENERATION_FAILED";
            failGeneration(block, requestId, clientIp, code);
        }
    }

    private void failGeneration(Map<String, Object> block, String requestId, String clientIp, String code) {
        long userId = number(block.get("userId"));
        jdbc.update("""
                UPDATE learning_block SET generation_status='FAILED',updated_at=?,updated_by=?,version=version+1
                WHERE id=? AND generation_status='GENERATING' AND deleted_at IS NULL
                """, Instant.now(), userId, block.get("id"));
        audit.recordAs(userId, requestId, clientIp, "LEARNING_BLOCK_GENERATE", "LEARNING_BLOCK",
                String.valueOf(block.get("publicId")), "GENERATING", code, "FAILED");
    }

    @Transactional
    public Map<String, Object> submit(String blockPublicId, Map<String, String> answers) {
        Map<String, Object> block = ownedBlock(blockPublicId);
        if (!"GENERATED".equals(block.get("generationStatus")))
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "请先生成并学习当前知识块");
        String taskStatus = String.valueOf(block.get("taskStatus"));
        if (!"COMPLETED".equals(taskStatus))
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "请先完成知识块中的学习任务，再进行块测");
        List<Map<String, Object>> questions = listOfMaps(block.get("testJson"));
        if (questions.isEmpty())
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "当前知识块没有可用测试");
        Map<String, String> supplied = answers == null ? Map.of() : answers;
        int correct = 0;
        List<Map<String, Object>> feedback = new ArrayList<>();
        for (Map<String, Object> question : questions) {
            String id = String.valueOf(question.get("id"));
            String expected = normalize(question.get("answer"));
            String actual = normalize(supplied.get(id));
            boolean passed = !expected.isBlank() && expected.equals(actual);
            if (passed) correct++;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("questionId", id);item.put("correct", passed);
            item.put("expectedAnswer", question.get("answer"));
            item.put("analysis", question.getOrDefault("analysis", ""));
            feedback.add(item);
        }
        BigDecimal score = BigDecimal.valueOf(correct * 100.0 / questions.size())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal passScore = new BigDecimal(String.valueOf(block.get("passScore")));
        boolean passed = score.compareTo(passScore) >= 0;
        long userId = number(block.get("userId"));
        long attemptId = IdWorker.getId();
        Instant submittedAt = Instant.now();
        jdbc.update("""
                INSERT INTO learning_block_attempt(
                  id,public_id,block_id,user_id,answers_json,score,passed,feedback_json,submitted_at
                ) VALUES(?,?,?,?,?,?,?,?,?)
                """, attemptId, UUID.randomUUID().toString(), block.get("id"), userId,
                toJson(supplied), score, passed, toJson(feedback), submittedAt);
        jdbc.update("""
                UPDATE learning_block
                SET latest_score=?,attempt_count=attempt_count+1,status=?,
                    completed_at=CASE WHEN ? THEN ? ELSE completed_at END,
                    updated_at=?,updated_by=?,version=version+1
                WHERE id=?
                """, score, passed ? "COMPLETED" : "ASSESSMENT_REQUIRED", passed,
                passed ? Instant.now() : null, Instant.now(), userId, block.get("id"));
        assessments.recordLearningBlockEvidence(userId, number(block.get("taskId")), attemptId, score, submittedAt);
        emitAssessmentEvent(block, attemptId, score, passed, submittedAt);
        boolean goalReady = passed && count("""
                SELECT COUNT(*) FROM learning_block
                WHERE goal_id=? AND status<>'COMPLETED' AND deleted_at IS NULL
                """, block.get("goalId")) == 0;
        audit.record("LEARNING_BLOCK_TEST", "LEARNING_BLOCK", blockPublicId,
                null, score + ":" + (passed ? "PASSED" : "FAILED"), "SUCCESS");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("score", score);result.put("passScore", passScore);result.put("passed", passed);
        result.put("feedback", feedback);result.put("goalReadyToComplete", goalReady);
        result.put("block", view(ownedBlock(blockPublicId)));
        return result;
    }

    private void emitAssessmentEvent(Map<String, Object> block, long attemptId, BigDecimal score,
                                     boolean passed, Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", number(block.get("userId")));
        payload.put("goalId", number(block.get("goalId")));
        payload.put("taskId", number(block.get("taskId")));
        payload.put("attemptId", attemptId);
        payload.put("score", score);
        payload.put("passed", passed);
        jdbc.update("""
                INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,payload_json,correlation_id,
                                         status,attempts,next_retry_at,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, IdWorker.getId(), "LEARNING_BLOCK", String.valueOf(block.get("publicId")),
                "LearningBlockAssessed", toJson(payload), UUID.randomUUID().toString(),
                "PENDING", 0, occurredAt, occurredAt);
    }

    public void markAssessmentRequired(long taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id,public_id,generation_status,status
                FROM learning_block WHERE task_id=? AND deleted_at IS NULL
                """, taskId);
        if (rows.isEmpty()) return;
        Map<String, Object> block = rows.get(0);
        if (!"GENERATED".equals(block.get("generation_status")))
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "请先打开知识块并生成资料、练习和块测，再完成学习任务");
        jdbc.update("""
                UPDATE learning_block
                SET status='ASSESSMENT_REQUIRED',updated_at=?,updated_by=?,version=version+1
                WHERE id=? AND status<>'COMPLETED'
                """, Instant.now(), SecurityUtils.currentUserId(), block.get("id"));
    }

    private Map<String, Object> view(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("publicId", source.get("publicId"));result.put("sequenceNo", source.get("sequenceNo"));
        result.put("title", source.get("title"));result.put("objective", source.get("objective"));
        result.put("directionName", source.get("directionName"));
        result.put("explorationRequired", source.get("explorationRequired"));
        result.put("sourceStatus", source.get("sourceStatus"));result.put("generationStatus", source.get("generationStatus"));
        result.put("status", source.get("status"));result.put("passScore", source.get("passScore"));
        result.put("latestScore", source.get("latestScore"));result.put("attemptCount", source.get("attemptCount"));
        result.put("materialMarkdown", source.get("materialMarkdown"));
        result.put("sources", browserSources(listOfMaps(source.get("sourceManifestJson"))));
        result.put("sourceQueries", stringList(source.get("sourceQueriesJson")));
        result.put("exercises", listOfMaps(source.get("exercisesJson")));
        result.put("testQuestions", safeQuestions(listOfMaps(source.get("testJson"))));
        result.put("taskStatus", source.get("taskStatus"));
        result.put("effectiveSeconds", source.get("effectiveSeconds"));
        return result;
    }

    private List<Map<String, Object>> browserSources(List<Map<String, Object>> sources) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> source : sources) {
            Map<String, Object> item = new LinkedHashMap<>(source);
            if (item.get("chunkId") != null) item.put("chunkId", String.valueOf(item.get("chunkId")));
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> ownedByTask(String taskPublicId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT block.id,block.public_id AS publicId,block.user_id AS userId,block.goal_id AS goalId,
                       block.task_id AS taskId,block.sequence_no AS sequenceNo,block.title,block.objective,
                       block.direction_name AS directionName,block.exploration_required AS explorationRequired,
                       block.source_status AS sourceStatus,block.source_manifest_json AS sourceManifestJson,
                       block.source_queries_json AS sourceQueriesJson,block.generation_status AS generationStatus,
                       block.material_markdown AS materialMarkdown,block.exercises_json AS exercisesJson,
                       block.test_json AS testJson,block.pass_score AS passScore,block.latest_score AS latestScore,
                       block.attempt_count AS attemptCount,block.status,task.lifecycle_status AS taskStatus,
                       COALESCE((SELECT SUM(session.effective_seconds) FROM study_session session
                         WHERE session.task_id=task.id AND session.status='COMPLETED' AND session.deleted_at IS NULL),0) AS effectiveSeconds,
                       COALESCE((SELECT direction.current_stage FROM user_profile_direction direction
                         JOIN user_profile profile ON profile.id=direction.profile_id
                         WHERE profile.user_id=block.user_id AND direction.status='ACTIVE' AND direction.deleted_at IS NULL
                         ORDER BY direction.is_primary DESC,direction.id DESC LIMIT 1),'BEGINNER') AS currentStage
                FROM learning_block block
                JOIN learning_task task ON task.id=block.task_id
                WHERE task.public_id=? AND block.user_id=? AND block.deleted_at IS NULL
                """, taskPublicId, SecurityUtils.currentUserId());
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "当前任务还没有知识块");
        return rows.get(0);
    }

    private Map<String, Object> ownedBlock(String publicId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT block.id,block.public_id AS publicId,block.user_id AS userId,block.goal_id AS goalId,
                       block.task_id AS taskId,block.sequence_no AS sequenceNo,block.title,block.objective,
                       block.direction_name AS directionName,block.exploration_required AS explorationRequired,
                       block.source_status AS sourceStatus,block.source_manifest_json AS sourceManifestJson,
                       block.source_queries_json AS sourceQueriesJson,block.generation_status AS generationStatus,
                       block.material_markdown AS materialMarkdown,block.exercises_json AS exercisesJson,
                       block.test_json AS testJson,block.pass_score AS passScore,block.latest_score AS latestScore,
                       block.attempt_count AS attemptCount,block.status,task.lifecycle_status AS taskStatus,
                       COALESCE((SELECT SUM(session.effective_seconds) FROM study_session session
                         WHERE session.task_id=task.id AND session.status='COMPLETED' AND session.deleted_at IS NULL),0) AS effectiveSeconds,
                       COALESCE((SELECT direction.current_stage FROM user_profile_direction direction
                         JOIN user_profile profile ON profile.id=direction.profile_id
                         WHERE profile.user_id=block.user_id AND direction.status='ACTIVE' AND direction.deleted_at IS NULL
                         ORDER BY direction.is_primary DESC,direction.id DESC LIMIT 1),'BEGINNER') AS currentStage
                FROM learning_block block
                JOIN learning_task task ON task.id=block.task_id
                WHERE block.public_id=? AND block.user_id=? AND block.deleted_at IS NULL
                """, publicId, SecurityUtils.currentUserId());
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识块不存在");
        return rows.get(0);
    }

    private List<Map<String, Object>> withGenerationNote(
            List<Map<String, Object>> manifest,
            PythonAiServiceClient.LearningBlockContentResult generated) {
        List<Map<String, Object>> result = new ArrayList<>(manifest);
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("sourceType", "GENERATION_NOTE");note.put("title", "Agent 生成说明");
        note.put("quotePreview", generated.sourceNotes().isEmpty()
                ? "内容按当前来源生成，请结合块测验证掌握情况。"
                : String.join("；", generated.sourceNotes()));
        result.add(note);
        return result;
    }

    private List<Map<String, Object>> safeQuestions(List<Map<String, Object>> questions) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> question : questions) {
            Map<String, Object> safe = new LinkedHashMap<>(question);
            safe.remove("answer");safe.remove("analysis");result.add(safe);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return List.of();
        if (value instanceof List<?> list) return (List<Map<String, Object>>) list;
        try {
            return json.readValue(String.valueOf(value), new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("知识块 JSON 数据损坏", error);
        }
    }

    private List<String> stringList(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return List.of();
        try {
            return json.readValue(String.valueOf(value), new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("知识块检索词数据损坏", error);
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
    }

    private String normalize(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    private long number(Object value) {
        return ((Number) value).longValue();
    }

    private Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) return booleanValue;
        if (value instanceof Number number) return number.intValue() != 0;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private long count(String sql, Object... args) {
        Number value = jdbc.queryForObject(sql, Number.class, args);
        return value == null ? 0 : value.longValue();
    }

    private String abbreviate(String value, int max) {
        if (value == null) return null;
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}

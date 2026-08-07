package com.adaptivelearning.knowledgebase.application;

import com.adaptivelearning.knowledgebase.domain.KnowledgeSpaceEntity;
import com.adaptivelearning.knowledgebase.domain.QaCitationEntity;
import com.adaptivelearning.knowledgebase.domain.QaFeedbackEntity;
import com.adaptivelearning.knowledgebase.domain.QaMessageEntity;
import com.adaptivelearning.knowledgebase.domain.QaSessionEntity;
import com.adaptivelearning.knowledgebase.infrastructure.KnowledgeMappers.QaCitationMapper;
import com.adaptivelearning.knowledgebase.infrastructure.KnowledgeMappers.QaFeedbackMapper;
import com.adaptivelearning.knowledgebase.infrastructure.KnowledgeMappers.QaMessageMapper;
import com.adaptivelearning.knowledgebase.infrastructure.KnowledgeMappers.QaSessionMapper;
import com.adaptivelearning.shared.ai.RagAnswerGenerator;
import com.adaptivelearning.shared.ai.AiModelException;
import com.adaptivelearning.shared.ai.PythonAiServiceClient;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeQaService {
    private final KnowledgeDocumentService documents;
    private final QaSessionMapper sessionMapper;
    private final QaMessageMapper messageMapper;
    private final QaCitationMapper citationMapper;
    private final QaFeedbackMapper feedbackMapper;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TextVectorizer vectorizer;
    private final RagAnswerGenerator answerGenerator;
    private final PythonAiServiceClient pythonAi;

    @Value("${app.rag.top-k:5}")
    private int defaultTopK;

    public record SearchHit(String citationId, String documentId, String documentVersionId, long chunkId,
                            @JsonIgnore long documentDbId,
                            String fileName, List<String> titlePath, Integer pageFrom, Integer pageTo,
                            String quotePreview, double score) {}

    public record SearchResult(boolean evidenceSufficient, List<SearchHit> hits, long latencyMs) {}
    public record MessageView(QaMessageEntity message, List<CitationView> citations) {}

    /** 引用视图：附带来源文档名与页码，供前端引用卡片展示出处。 */
    public record CitationView(String citationCode, Long chunkId, Long documentVersionId, String quotePreview,
                               Integer rankNo, BigDecimal scoreSnapshot, String accessStatus,
                               String fileName, Integer pageFrom, Integer pageTo) {}
    public record AnswerResult(MessageView userMessage, MessageView assistantMessage, boolean evidenceSufficient) {}

    private record Candidate(long chunkId, long versionId, long documentDbId, String documentId,
                             String fileName, String text,
                             String vectorJson, Integer pageFrom, Integer pageTo, String titlePathJson) {}

    public SearchResult search(String query, List<String> spaceIds, Integer topK) {
        long begin = System.nanoTime();
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "检索问题不能为空");
        }
        if (spaceIds == null || spaceIds.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "至少选择一个知识空间");
        }
        List<Long> ids = spaceIds.stream().map(documents::accessibleSpace).map(KnowledgeSpaceEntity::getId).toList();
        return searchScoped(query, List.of(), ids, topK, begin);
    }

    /** 按具体文件检索：SQL 对本人/公开资料二次鉴权后，才把文档 ID 交给 Python 预过滤。 */
    public SearchResult searchDocuments(String query, List<String> documentIds, Integer topK) {
        long begin = System.nanoTime();
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "检索问题不能为空");
        }
        if (documentIds == null || documentIds.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "至少选择一个文件");
        }
        return searchScoped(query, documentIds, List.of(), topK, begin);
    }

    private SearchResult searchScoped(String query, List<String> documentPublicIds,
                                      List<Long> spaceDbIds, Integer topK, long begin) {
        int limit = Math.min(Math.max(1, topK == null ? defaultTopK : topK), 20);
        if (!pythonAi.isConfigured()) {
            throw new AiModelException(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE);
        }
        List<Candidate> authorized = documentPublicIds.isEmpty()
                ? authorizedCandidates(spaceDbIds)
                : authorizedDocumentCandidates(documentPublicIds);
        if (authorized.isEmpty()) {
            return new SearchResult(false, List.of(), elapsedMs(begin));
        }
        List<Long> documentDbIds = authorized.stream().map(Candidate::documentDbId).distinct().toList();
        return pythonSearch(query, spaceDbIds, documentDbIds, authorized, limit, begin);
    }

    private SearchResult pythonSearch(String query, List<Long> allowedSpaceIds,
                                      List<Long> allowedDocumentIds, List<Candidate> authorized,
                                      int limit, long begin) {
        PythonAiServiceClient.SearchResult result = pythonAi.search(
                SecurityUtils.currentUserId(), query, allowedSpaceIds, allowedDocumentIds, List.of(),
                limit, Math.max(20, limit));
        Map<Long, Candidate> byChunk = authorized.stream()
                .collect(java.util.stream.Collectors.toMap(Candidate::chunkId, item -> item));
        List<SearchHit> hits = new ArrayList<>();
        for (PythonAiServiceClient.SearchHit item : result.hits()) {
            Candidate candidate = byChunk.get(item.chunkId());
            if (candidate == null || candidate.versionId() != item.documentVersionId()
                    || candidate.documentDbId() != item.documentId()) {
                continue;
            }
            hits.add(new SearchHit("S" + (hits.size() + 1), candidate.documentId(),
                    String.valueOf(candidate.versionId()), candidate.chunkId(), candidate.documentDbId(),
                    candidate.fileName(), parseTitlePath(candidate.titlePathJson()),
                    candidate.pageFrom(), candidate.pageTo(), preview(candidate.text()), item.score()));
            if (hits.size() == limit) break;
        }
        return new SearchResult(result.evidenceSufficient() && !hits.isEmpty(), List.copyOf(hits), elapsedMs(begin));
    }

    private SearchResult legacySearch(String query, List<Long> ids, int limit, long begin) {
        List<Candidate> candidates = authorizedCandidates(ids);
        double[] queryVector = vectorizer.vector(query);
        record Scored(Candidate candidate, double score) {}
        List<Scored> scored = candidates.stream()
                .map(candidate -> {
                    double cosine = vectorizer.cosine(queryVector, readVector(candidate.vectorJson()));
                    double keyword = vectorizer.keyword(query, candidate.text());
                    return new Scored(candidate, 0.7 * Math.max(0, cosine) + 0.3 * keyword);
                })
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(limit)
                .toList();

        boolean enough = !scored.isEmpty() && scored.get(0).score() >= 0.08;
        List<SearchHit> hits = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            Scored item = scored.get(i);
            Candidate candidate = item.candidate();
            hits.add(new SearchHit("S" + (i + 1), candidate.documentId(), String.valueOf(candidate.versionId()),
                    candidate.chunkId(), candidate.documentDbId(), candidate.fileName(),
                    parseTitlePath(candidate.titlePathJson()),
                    candidate.pageFrom(), candidate.pageTo(),
                    preview(candidate.text()), item.score()));
        }
        return new SearchResult(enough, hits, elapsedMs(begin));
    }

    private List<Candidate> authorizedCandidates(List<Long> ids) {
        return authorizedBy("d.space_id", ids);
    }

    private List<Candidate> authorizedDocumentCandidates(List<String> publicIds) {
        return authorizedBy("d.public_id", publicIds);
    }

    private List<Candidate> authorizedBy(String column, List<?> values) {
        String marks = String.join(",", Collections.nCopies(values.size(), "?"));
        List<Object> args = new ArrayList<>(values);
        args.add(SecurityUtils.currentUserId());
        return jdbc.query("""
                        SELECT c.id,c.document_version_id,d.id,d.public_id,d.display_name,c.text,c.vector_json,
                               c.page_from,c.page_to,c.title_path_json
                        FROM knowledge_chunk c
                        JOIN document_version v ON v.id=c.document_version_id
                        JOIN knowledge_document d ON d.id=v.document_id
                        JOIN knowledge_space s ON s.id=d.space_id
                        """ + "WHERE " + column + " IN (" + marks + ") AND d.status='INDEXED'"
                        + " AND v.version_no=d.active_version_no AND (s.visibility='PUBLIC' OR s.user_id=?)",
                (rs, row) -> new Candidate(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7),
                        (Integer) rs.getObject(8), (Integer) rs.getObject(9), rs.getString(10)),
                args.toArray());
    }

    public QaSessionEntity createSession(String title, List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "至少选择一个文件");
        }
        documentIds.forEach(documents::detail);
        QaSessionEntity session = new QaSessionEntity();
        session.setPublicId(UUID.randomUUID().toString());
        session.setUserId(SecurityUtils.currentUserId());
        session.setTitle(title == null || title.isBlank() ? "新对话" : title);
        session.setSelectedSpaceJson(toJson(Map.of("scope", "DOCUMENT", "ids", documentIds)));
        session.setStatus("ACTIVE");
        sessionMapper.insert(session);
        return session;
    }

    public List<QaSessionEntity> sessions() {
        return sessionMapper.selectList(new LambdaQueryWrapper<QaSessionEntity>()
                .eq(QaSessionEntity::getUserId, SecurityUtils.currentUserId())
                .orderByDesc(QaSessionEntity::getUpdatedAt));
    }

    public QaSessionEntity renameSession(String sessionId, String title) {
        if (title == null || title.isBlank() || title.length() > 200) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "对话标题不能为空且不能超过 200 字");
        }
        QaSessionEntity session = ownedSession(sessionId);
        session.setTitle(title.trim());
        sessionMapper.updateById(session);
        return session;
    }

    @Transactional
    public void deleteSession(String sessionId) {
        QaSessionEntity session = ownedSession(sessionId);
        List<QaMessageEntity> msgs = messageMapper.selectList(new LambdaQueryWrapper<QaMessageEntity>()
                .eq(QaMessageEntity::getSessionId, session.getId()));
        if (!msgs.isEmpty()) {
            List<Long> messageIds = msgs.stream().map(QaMessageEntity::getId).toList();
            citationMapper.delete(new LambdaQueryWrapper<QaCitationEntity>()
                    .in(QaCitationEntity::getMessageId, messageIds));
            feedbackMapper.delete(new LambdaQueryWrapper<QaFeedbackEntity>()
                    .in(QaFeedbackEntity::getMessageId, messageIds));
            messageMapper.delete(new LambdaQueryWrapper<QaMessageEntity>()
                    .in(QaMessageEntity::getId, messageIds));
        }
        sessionMapper.deleteById(session.getId());
    }

    public AnswerResult ask(String sessionId, String question) {
        return askStreaming(sessionId, question, ignored -> { }, ignored -> { }, ignored -> { });
    }

    public AnswerResult askStreaming(String sessionId, String question, Consumer<String> answerDelta,
                                     Consumer<String> answerReplacement, Consumer<SearchHit> citationReady) {
        long begin = System.nanoTime();
        long userId = SecurityUtils.currentUserId();
        QaSessionEntity session = ownedSession(sessionId);
        QaMessageEntity user = message(session, "USER", question, null, null, null, 0);
        SearchScope scope = scopeOf(session.getSelectedSpaceJson());
        SearchResult result = "DOCUMENT".equals(scope.type())
                ? searchDocuments(question, scope.ids(), defaultTopK)
                : search(question, scope.ids(), defaultTopK);

        String content;
        String answerMode;
        String evidenceLevel;
        Long modelRunId = null;
        Set<String> citedIds = Set.of();
        if (!result.evidenceSufficient()) {
            content = "当前选择的个人资料中没有足够证据回答这个问题。你可以补充资料或扩大已授权范围。";
            answerMode = "RAG";
            evidenceLevel = "INSUFFICIENT";
        } else {
            result.hits().forEach(citationReady);
            List<RagAnswerGenerator.Evidence> evidence = result.hits().stream()
                    .map(hit -> new RagAnswerGenerator.Evidence(hit.citationId(), hit.chunkId(),
                            hit.documentDbId(), Long.parseLong(hit.documentVersionId()),
                            hit.fileName(), hit.quotePreview(),
                            hit.titlePath(), hit.pageFrom(), hit.pageTo()))
                    .toList();
            RagAnswerGenerator.GeneratedAnswer answer = answerGenerator.generateStreaming(
                    userId, question, evidence, answerDelta);
            content = answer.content();
            answerMode = answer.answerMode();
            evidenceLevel = "SUFFICIENT";
            modelRunId = answer.modelRunId();
            citedIds = answer.citationIds();
            if (answer.replacementRequired()) answerReplacement.accept(content);
        }

        QaMessageEntity assistant = message(session, "ASSISTANT", content, answerMode, evidenceLevel,
                modelRunId, elapsedMs(begin));
        int rank = 1;
        for (SearchHit hit : result.hits()) {
            if (!citedIds.contains(hit.citationId())) {
                continue;
            }
            QaCitationEntity citation = new QaCitationEntity();
            citation.setMessageId(assistant.getId());
            citation.setCitationCode(hit.citationId());
            citation.setChunkId(hit.chunkId());
            citation.setDocumentVersionId(Long.parseLong(hit.documentVersionId()));
            citation.setQuotePreview(hit.quotePreview());
            citation.setRankNo(rank++);
            citation.setScoreSnapshot(BigDecimal.valueOf(hit.score()).setScale(6, RoundingMode.HALF_UP));
            citation.setAccessStatus("AVAILABLE");
            citationMapper.insert(citation);
        }
        return new AnswerResult(view(user), view(assistant), result.evidenceSufficient());
    }

    public List<MessageView> messages(String sessionId) {
        QaSessionEntity session = ownedSession(sessionId);
        return messageMapper.selectList(new LambdaQueryWrapper<QaMessageEntity>()
                        .eq(QaMessageEntity::getSessionId, session.getId())
                        .orderByAsc(QaMessageEntity::getCreatedAt))
                .stream().map(this::view).toList();
    }

    public void feedback(String messageId, int rating, String reason, String comment) {
        if (rating < 1 || rating > 5) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "反馈评分必须为 1～5");
        }
        QaMessageEntity message = messageMapper.selectOne(new LambdaQueryWrapper<QaMessageEntity>()
                .eq(QaMessageEntity::getPublicId, messageId));
        if (message == null) {
            notFound();
        }
        QaSessionEntity session = sessionMapper.selectById(message.getSessionId());
        if (session == null || !session.getUserId().equals(SecurityUtils.currentUserId())) {
            notFound();
        }
        QaFeedbackEntity feedback = feedbackMapper.selectOne(new LambdaQueryWrapper<QaFeedbackEntity>()
                .eq(QaFeedbackEntity::getMessageId, message.getId())
                .eq(QaFeedbackEntity::getUserId, session.getUserId()));
        if (feedback == null) {
            feedback = new QaFeedbackEntity();
            feedback.setMessageId(message.getId());
            feedback.setUserId(session.getUserId());
            feedback.setCreatedAt(Instant.now());
        }
        feedback.setRating(rating);
        feedback.setReasonCode(reason);
        feedback.setComment(comment);
        if (feedback.getId() == null) {
            feedbackMapper.insert(feedback);
        } else {
            feedbackMapper.updateById(feedback);
        }
    }

    private QaMessageEntity message(QaSessionEntity session, String role, String content, String mode,
                                    String evidence, Long modelRunId, long latency) {
        if (content == null || content.isBlank() || content.length() > 10_000) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "消息不能为空且不能超过 10000 字");
        }
        QaMessageEntity message = new QaMessageEntity();
        message.setPublicId(UUID.randomUUID().toString());
        message.setSessionId(session.getId());
        message.setRole(role);
        message.setContent(content);
        message.setAnswerMode(mode);
        message.setEvidenceLevel(evidence);
        message.setModelRunId(modelRunId);
        message.setLatencyMs(latency);
        message.setCreatedAt(Instant.now());
        messageMapper.insert(message);
        return message;
    }

    private MessageView view(QaMessageEntity message) {
        List<QaCitationEntity> citations = citationMapper.selectList(new LambdaQueryWrapper<QaCitationEntity>()
                .eq(QaCitationEntity::getMessageId, message.getId())
                .orderByAsc(QaCitationEntity::getRankNo));
        return new MessageView(message, citations.stream().map(this::citationView).toList());
    }

    private CitationView citationView(QaCitationEntity citation) {
        Map<String, Object> meta = jdbc.query("""
                SELECT d.display_name AS fileName, ch.page_from AS pageFrom, ch.page_to AS pageTo
                FROM knowledge_chunk ch
                JOIN document_version v ON v.id=ch.document_version_id
                JOIN knowledge_document d ON d.id=v.document_id
                WHERE ch.id=?
                """, rs -> {
            Map<String, Object> result = new HashMap<>();
            if (rs.next()) {
                result.put("fileName", rs.getString(1));
                result.put("pageFrom", rs.getObject(2));
                result.put("pageTo", rs.getObject(3));
            }
            return result;
        }, citation.getChunkId());
        return new CitationView(citation.getCitationCode(), citation.getChunkId(),
                citation.getDocumentVersionId(), citation.getQuotePreview(), citation.getRankNo(),
                citation.getScoreSnapshot(), citation.getAccessStatus(),
                (String) meta.get("fileName"), (Integer) meta.get("pageFrom"), (Integer) meta.get("pageTo"));
    }

    /** 会话检索范围：新版按文件（{"scope":"DOCUMENT","ids":[...]}），旧版是空间 publicId 数组。 */
    private record SearchScope(String type, List<String> ids) {}

    private SearchScope scopeOf(String raw) {
        if (raw != null && raw.trim().startsWith("{")) {
            try {
                JsonNode node = json.readTree(raw);
                List<String> ids = new ArrayList<>();
                node.path("ids").forEach(item -> ids.add(item.asText()));
                return new SearchScope("DOCUMENT", ids);
            } catch (Exception ignored) {
                // 非法 JSON 按空间范围回退
            }
        }
        try {
            return new SearchScope("SPACE", json.readValue(raw, new TypeReference<>() {}));
        } catch (Exception e) {
            return new SearchScope("SPACE", List.of());
        }
    }

    private QaSessionEntity ownedSession(String id) {
        QaSessionEntity session = sessionMapper.selectOne(new LambdaQueryWrapper<QaSessionEntity>()
                .eq(QaSessionEntity::getPublicId, id)
                .eq(QaSessionEntity::getUserId, SecurityUtils.currentUserId()));
        if (session == null) {
            notFound();
        }
        return session;
    }

    private double[] readVector(String value) {
        try {
            return json.readValue(value, double[].class);
        } catch (Exception e) {
            return new double[128];
        }
    }

    private List<String> parseTitlePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String preview(String text) {
        String value = text.replaceAll("\\s+", " ").trim();
        return value.substring(0, Math.min(300, value.length()));
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private long elapsedMs(long begin) {
        return (System.nanoTime() - begin) / 1_000_000;
    }

    private void notFound() {
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "资源不存在");
    }
}

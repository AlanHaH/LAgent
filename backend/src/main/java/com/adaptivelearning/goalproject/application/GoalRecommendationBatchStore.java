package com.adaptivelearning.goalproject.application;

import com.adaptivelearning.profile.domain.ProfileVersionEntity;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class GoalRecommendationBatchStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public record VerifiedRecommendation(String sourceType, long profileVersionId, int profileVersionNo,
                                         GoalRecommendationService.Recommendation candidate,
                                         GoalRecommendationContext context) { }

    @Transactional
    public void save(long userId, ProfileVersionEntity version,
                     GoalRecommendationService.RecommendationResponse response) {
        assertCurrentProfile(userId, version.getId(), version.getVersionNo(), true);
        try {
            jdbc.update("""
                    INSERT INTO goal_recommendation_batch(
                      id,public_id,user_id,profile_version_id,profile_version_no,source,
                      response_json,generated_at,created_at
                    ) VALUES(?,?,?,?,?,?,?,?,?)
                    """, IdWorker.getId(), UUID.randomUUID().toString(), userId, version.getId(),
                    version.getVersionNo(), response.source(), objectMapper.writeValueAsString(response),
                    response.generatedAt(), Instant.now());
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("目标推荐结果序列化失败", error);
        }
    }

    public GoalRecommendationService.RecommendationResponse latest(long userId) {
        List<String> payloads = jdbc.query("""
                SELECT response_json
                FROM goal_recommendation_batch
                WHERE user_id=?
                ORDER BY generated_at DESC,id DESC
                LIMIT 1
                """, (rs, row) -> rs.getString(1), userId);
        if (payloads.isEmpty()) return null;
        return parse(payloads.get(0));
    }

    @Transactional
    public VerifiedRecommendation verifyForAdoption(long userId, String recommendationId,
                                                     String requestedSourceType, Long requestedProfileVersionId) {
        if (recommendationId == null || recommendationId.isBlank()
                || requestedProfileVersionId == null || requestedSourceType == null) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "推荐来源信息不完整");
        }
        List<Map<String, Object>> batches = jdbc.queryForList("""
                SELECT b.profile_version_id,b.profile_version_no,b.source,b.response_json,pv.snapshot_json
                FROM goal_recommendation_batch b
                JOIN profile_version pv ON pv.id=b.profile_version_id
                WHERE b.user_id=? AND b.profile_version_id=?
                ORDER BY generated_at DESC,id DESC
                """, userId, requestedProfileVersionId);
        for (Map<String, Object> row : batches) {
            GoalRecommendationService.RecommendationResponse response = parse(
                    Objects.toString(row.get("response_json"), ""));
            GoalRecommendationService.Recommendation candidate = response.recommendations().stream()
                    .filter(item -> recommendationId.equals(item.id())).findFirst().orElse(null);
            if (candidate == null) continue;
            long profileVersionId = ((Number) row.get("profile_version_id")).longValue();
            int profileVersionNo = ((Number) row.get("profile_version_no")).intValue();
            String sourceType = "AI".equals(response.source()) ? "AI_RECOMMENDED" : "RULE_RECOMMENDED";
            if (!Objects.equals(requestedProfileVersionId, profileVersionId)
                    || !sourceType.equals(requestedSourceType)
                    || !Objects.equals(candidate.source(), response.source())) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "推荐来源与服务端批次不一致");
            }
            assertCurrentProfile(userId, profileVersionId, profileVersionNo, true);
            return new VerifiedRecommendation(sourceType, profileVersionId, profileVersionNo, candidate,
                    GoalRecommendationContext.from(profileVersionId, profileVersionNo,
                            Objects.toString(row.get("snapshot_json"), ""), objectMapper));
        }
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "推荐候选不存在或不属于当前用户");
    }

    private void assertCurrentProfile(long userId, long profileVersionId, int profileVersionNo, boolean lock) {
        String sql = """
                SELECT p.profile_status,p.current_version_no,pv.id profile_version_id
                FROM user_profile p
                JOIN profile_version pv ON pv.profile_id=p.id AND pv.version_no=p.current_version_no
                WHERE p.user_id=?
                """ + (lock ? " FOR UPDATE" : "");
        List<Map<String, Object>> rows = jdbc.queryForList(sql, userId);
        if (rows.isEmpty() || !"GENERATED".equals(rows.get(0).get("profile_status"))
                || ((Number) rows.get(0).get("current_version_no")).intValue() != profileVersionNo
                || ((Number) rows.get(0).get("profile_version_id")).longValue() != profileVersionId) {
            throw new BusinessException(ErrorCode.PROFILE_CONTEXT_STALE,
                    "画像已更新，旧目标推荐已失效，请重新生成推荐");
        }
    }

    private GoalRecommendationService.RecommendationResponse parse(String payload) {
        try {
            return objectMapper.readValue(payload, GoalRecommendationService.RecommendationResponse.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("目标推荐批次数据无法解析", error);
        }
    }
}

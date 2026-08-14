package com.adaptivelearning.planning.application;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** M05 私有的提案上下文与发布 CAS 规则，不引入通用指纹平台。 */
public final class PlanningContextPolicy {
    private PlanningContextPolicy() { }

    public static String fingerprint(ObjectMapper objectMapper, Map<String, Object> context) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(context);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("无法计算规划上下文指纹", error);
        }
    }

    public static String fingerprint(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNullElse(payload, "").getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public static void requirePublicationCas(Integer expectedVersion, String expectedTaskFingerprint,
                                             Integer currentVersion, String currentTaskFingerprint) {
        if (!Objects.equals(expectedVersion, currentVersion)
                || !Objects.equals(expectedTaskFingerprint, currentTaskFingerprint)) {
            throw new BusinessException(ErrorCode.PLAN_CONTEXT_STALE,
                    "正式计划版本或任务集合已变化，请重新生成提案");
        }
    }

    public static void requireState(String current, String operation, String... allowed) {
        if (!Set.copyOf(Arrays.asList(allowed)).contains(current)) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
                    "当前计划版本状态不能" + operation);
        }
    }

    public static void requireTaskScope(long planUserId, long planGoalId, Long planProjectId,
                                        Long taskUserId, Long taskGoalId, Long taskProjectId) {
        if (!Objects.equals(planUserId, taskUserId)
                || !Objects.equals(planGoalId, taskGoalId)
                || !Objects.equals(planProjectId, taskProjectId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在或不属于当前计划范围");
        }
    }
}

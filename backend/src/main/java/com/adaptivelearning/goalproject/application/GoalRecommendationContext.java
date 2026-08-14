package com.adaptivelearning.goalproject.application;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Immutable recommendation input reconstructed only from one formal profile version snapshot. */
public record GoalRecommendationContext(
        long profileVersionId,
        int profileVersionNo,
        LocalDate planStartDate,
        LocalDate planEndDate,
        String backgroundText,
        List<Direction> directions,
        Map<String, Object> preference,
        int weeklyAvailableMinutes,
        BigDecimal capacityRatio,
        int selfAssessmentCount,
        BigDecimal confidence,
        int recommendedDifficulty,
        int dailyRecommendedTasks,
        List<String> riskNotices) {

    public record Direction(Long id, String name, String currentStage, boolean primary) {
        public String key() {
            return id == null ? "custom:" + normalize(name) : "catalog:" + id;
        }
    }

    public static GoalRecommendationContext from(long profileVersionId, int profileVersionNo,
                                                  String snapshotJson, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(snapshotJson);
            LocalDate start = LocalDate.parse(requiredText(root, "planStartDate"));
            LocalDate end = LocalDate.parse(requiredText(root, "planEndDate"));
            if (end.isBefore(start)) invalidSnapshot();

            List<Direction> directions = new ArrayList<>();
            for (JsonNode item : root.path("directions")) {
                String name = item.path("name").asText("").trim();
                String stage = item.path("currentStage").asText("").trim();
                if (name.isBlank() || !List.of("BEGINNER", "INTERMEDIATE", "ADVANCED").contains(stage)) {
                    invalidSnapshot();
                }
                Long id = null;
                if (!item.path("directionId").isNull() && !item.path("directionId").asText("").isBlank()) {
                    id = Long.valueOf(item.path("directionId").asText());
                }
                directions.add(new Direction(id, name, stage, item.path("primary").asBoolean(false)));
            }
            if (directions.isEmpty()) invalidSnapshot();
            if (directions.stream().noneMatch(Direction::primary)
                    || directions.stream().map(Direction::key).distinct().count() != directions.size()) {
                invalidSnapshot();
            }

            JsonNode preferenceNode = root.path("preference");
            Map<String, Object> preference = preferenceNode.isObject()
                    ? objectMapper.convertValue(preferenceNode, objectMapper.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class))
                    : Map.of();
            BigDecimal capacityRatio = preferenceNode.path("capacityRatio").isNumber()
                    ? preferenceNode.path("capacityRatio").decimalValue() : new BigDecimal("0.85");
            if (capacityRatio.signum() <= 0 || capacityRatio.compareTo(BigDecimal.ONE) > 0) invalidSnapshot();
            int weekly = root.path("weeklyAvailableMinutes").asInt(0);
            int selfAssessmentCount = root.path("source").path("selfAssessmentCount").asInt(0);
            BigDecimal confidence = root.path("confidence").decimalValue();
            int difficulty = root.path("recommendedDifficulty").asInt(0);
            int dailyTasks = root.path("dailyRecommendedTasks").asInt(0);
            if (weekly <= 0 || weekly > 10080 || selfAssessmentCount < 0
                    || confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0
                    || difficulty < 1 || difficulty > 5 || dailyTasks < 1) invalidSnapshot();

            List<String> risks = new ArrayList<>();
            root.path("riskNotices").forEach(item -> risks.add(item.asText()));
            return new GoalRecommendationContext(profileVersionId, profileVersionNo, start, end,
                    root.path("backgroundText").isNull() ? null : root.path("backgroundText").asText(),
                    List.copyOf(directions), java.util.Collections.unmodifiableMap(new LinkedHashMap<>(preference)),
                    weekly, capacityRatio,
                    selfAssessmentCount, confidence, difficulty, dailyTasks, List.copyOf(risks));
        } catch (JsonProcessingException | DateTimeException | NumberFormatException error) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "正式画像版本快照无法用于目标推荐");
        }
    }

    public int weeklyCapacityMinutes() {
        return BigDecimal.valueOf(weeklyAvailableMinutes).multiply(capacityRatio).intValue();
    }

    public Direction requireDirection(Long directionId, String customDirection) {
        if ((directionId == null) == (customDirection == null || customDirection.isBlank())) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "目录方向和自定义方向必须且只能提供一个");
        }
        return directions.stream().filter(item -> directionId == null
                        ? item.id() == null && normalize(item.name()).equals(normalize(customDirection))
                        : Objects.equals(item.id(), directionId))
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                        "目标方向必须来自该正式画像版本"));
    }

    private static String requiredText(JsonNode root, String field) {
        String value = root.path(field).asText("");
        if (value.isBlank()) invalidSnapshot();
        return value;
    }

    private static void invalidSnapshot() {
        throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "正式画像版本快照缺少目标推荐所需字段");
    }

    static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_\\-—/\\\\｜|·•：:，,。.、()（）\\[\\]【】]+", "");
    }
}

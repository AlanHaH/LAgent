package com.adaptivelearning.execution.application;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.support.application.HashingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class TaskAcceptancePolicy {
    private final ObjectMapper objectMapper;
    private final HashingService hashing;

    public record Criterion(int index, String text) { }
    public record Snapshot(String snapshotHash, List<Criterion> criteria) { }
    public record Confirmation(String snapshotHash, List<Integer> confirmedIndexes) { }

    public Snapshot snapshot(String acceptanceJson) {
        try {
            JsonNode root = objectMapper.readTree(
                    acceptanceJson == null || acceptanceJson.isBlank() ? "[]" : acceptanceJson);
            if (root == null || !root.isArray()) invalidData();
            List<String> canonicalCriteria = new ArrayList<>();
            for (JsonNode item : root) {
                if (!item.isTextual() || item.asText().trim().isEmpty()) invalidData();
                canonicalCriteria.add(item.asText().trim());
            }
            String canonical = objectMapper.writeValueAsString(canonicalCriteria);
            List<Criterion> criteria = new ArrayList<>();
            for (int index = 0; index < canonicalCriteria.size(); index++) {
                criteria.add(new Criterion(index, canonicalCriteria.get(index)));
            }
            return new Snapshot(hashing.sha256(canonical), List.copyOf(criteria));
        } catch (JsonProcessingException failure) {
            invalidData();
            throw new IllegalStateException(failure);
        }
    }

    public void requireConfirmed(Snapshot snapshot, Confirmation confirmation) {
        if (snapshot.criteria().isEmpty()) return;
        if (confirmation == null || confirmation.snapshotHash() == null
                || !snapshot.snapshotHash().equals(confirmation.snapshotHash())) {
            throw new BusinessException(ErrorCode.TASK_ACCEPTANCE_STALE,
                    "任务验收条件已经变化，请刷新后重新确认");
        }
        List<Integer> indexes = confirmation.confirmedIndexes();
        if (indexes == null) invalidConfirmation();
        Set<Integer> unique = new HashSet<>(indexes);
        if (unique.size() != indexes.size() || unique.size() != snapshot.criteria().size()) {
            invalidConfirmation();
        }
        for (int index = 0; index < snapshot.criteria().size(); index++) {
            if (!unique.contains(index)) invalidConfirmation();
        }
    }

    private void invalidConfirmation() {
        throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                "必须逐项确认当前任务的全部验收条件");
    }

    private void invalidData() {
        throw new BusinessException(ErrorCode.DEPENDENCY_DATA_INVALID,
                "任务验收条件数据异常，已拒绝完成");
    }
}

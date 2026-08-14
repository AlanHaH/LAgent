package com.adaptivelearning.planning.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanningKnowledgePointIdCompatibilityTest {
    @Test
    void acceptsHistoricalNumericAndCurrentStringKnowledgePointIds() {
        long largeId = 9_007_199_254_741_234L;

        assertThat(PlanningService.parseKnowledgePointIds(List.of(10, 20L, largeId)))
                .containsExactly(10L, 20L, largeId);
        assertThat(PlanningService.parseKnowledgePointIds(List.of("10", "20", String.valueOf(largeId))))
                .containsExactly(10L, 20L, largeId);
    }
}

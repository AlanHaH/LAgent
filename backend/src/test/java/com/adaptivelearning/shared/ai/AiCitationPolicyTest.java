package com.adaptivelearning.shared.ai;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AiCitationPolicyTest {
    @Test
    void acceptsOnlyCitationsFromTheRetrievedEvidence() {
        assertThat(AiCitationPolicy.validCitations("结论 [S1]，补充 [S2]。", Set.of("S1", "S2")))
                .containsExactlyInAnyOrder("S1", "S2");
    }

    @Test
    void rejectsAnswersWithoutCitationsOrWithInventedCitations() {
        assertThat(AiCitationPolicy.validCitations("没有引用", Set.of("S1"))).isEmpty();
        assertThat(AiCitationPolicy.validCitations("伪造引用 [S9]", Set.of("S1"))).isEmpty();
    }
}

package com.adaptivelearning.profile.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicProfileAnalysisPolicyTest {
    private final DeterministicProfileAnalysisPolicy policy = new DeterministicProfileAnalysisPolicy();

    @Test
    void usesDocumentedMetricsWithoutSelfAssessmentEvidence() {
        var analysis = policy.analyze(0);

        assertThat(analysis.confidence()).isEqualByComparingTo(new BigDecimal("0.10"));
        assertThat(analysis.recommendedDifficulty()).isEqualTo(1);
        assertThat(analysis.dailyRecommendedTasks()).isEqualTo(2);
        assertThat(analysis.riskNotices()).containsExactly("尚无诊断或自评证据");
    }

    @Test
    void oneOrManySelfAssessmentsUseTheSameCappedMetrics() {
        var one = policy.analyze(1);
        var many = policy.analyze(100);

        assertThat(one).isEqualTo(many);
        assertThat(one.confidence()).isEqualByComparingTo(new BigDecimal("0.20"));
        assertThat(one.recommendedDifficulty()).isEqualTo(2);
        assertThat(one.dailyRecommendedTasks()).isEqualTo(2);
        assertThat(one.riskNotices()).containsExactly("当前仅含自评证据，建议完成诊断");
    }

    @Test
    void sameInputAlwaysProducesTheSameFormalMetrics() {
        assertThat(policy.analyze(3)).isEqualTo(policy.analyze(3));
    }
}

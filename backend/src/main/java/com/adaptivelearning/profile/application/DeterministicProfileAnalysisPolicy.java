package com.adaptivelearning.profile.application;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class DeterministicProfileAnalysisPolicy {
    public record Analysis(BigDecimal confidence, int recommendedDifficulty,
                           int dailyRecommendedTasks, List<String> riskNotices) { }

    public Analysis analyze(int selfAssessmentCount) {
        if (selfAssessmentCount <= 0) {
            return new Analysis(new BigDecimal("0.10"), 1, 2,
                    List.of("尚无诊断或自评证据"));
        }
        return new Analysis(new BigDecimal("0.20"), 2, 2,
                List.of("当前仅含自评证据，建议完成诊断"));
    }
}

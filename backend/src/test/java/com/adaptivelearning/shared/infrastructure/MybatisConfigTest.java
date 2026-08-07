package com.adaptivelearning.shared.infrastructure;

import com.adaptivelearning.goalproject.domain.LearningGoalEntity;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MybatisConfigTest {

    @Test
    void updateFillRefreshesUpdatedAtEvenWhenEntityCarriesOldValue() {
        Instant stale = Instant.parse("2026-01-01T00:00:00Z");
        LearningGoalEntity goal = new LearningGoalEntity();
        goal.setUpdatedAt(stale);
        goal.setUpdatedBy(42L);
        new MybatisConfig().auditMetaObjectHandler().updateFill(SystemMetaObject.forObject(goal));
        assertThat(goal.getUpdatedAt()).isAfter(stale);
        assertThat(goal.getUpdatedBy()).isZero();
    }
}

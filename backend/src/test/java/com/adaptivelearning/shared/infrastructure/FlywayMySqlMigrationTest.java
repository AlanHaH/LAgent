package com.adaptivelearning.shared.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class FlywayMySqlMigrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("adaptive_learning")
            .withUsername("learning")
            .withPassword("learning-test-password");

    @Test
    void allProductionMigrationsApplyToRealMySql() {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration/mysql")
                .load();

        var result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("17");
    }
}

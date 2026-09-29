package com.nms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class MigrationIT extends IntegrationTestBase {

    @Autowired
    JdbcClient jdbc;

    @Test
    void migrationsApplyAndSeedDataIsPresent() {
        assertThat(jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success").query(Integer.class).single())
                .isEqualTo(2);
        assertThat(jdbc.sql("SELECT source_system FROM api_client ORDER BY source_system").query(String.class).list())
                .containsExactly("billing", "legacy", "trading");
        assertThat(jdbc.sql("SELECT count(*) FROM recipient").query(Integer.class).single()).isEqualTo(11);
        assertThat(jdbc.sql("SELECT count(*) FROM recipient_channel").query(Integer.class).single()).isEqualTo(16);
    }
}

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
                .isEqualTo(3);
        assertThat(jdbc.sql("SELECT source_system FROM api_client ORDER BY source_system").query(String.class).list())
                .containsExactly("billing", "legacy", "trading");
        assertThat(jdbc.sql("SELECT count(*) FROM recipient").query(Integer.class).single()).isEqualTo(11);
        assertThat(jdbc.sql("SELECT count(*) FROM recipient_channel").query(Integer.class).single()).isEqualTo(16);
    }

    @Test
    void v3AddsSuppressionColumnConstraintsAndIndexes() {
        assertThat(jdbc.sql("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name = 'delivery' AND column_name = 'suppressed_by'
                """).query(String.class).single()).isEqualTo("YES");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM pg_constraint
                WHERE conname IN ('delivery_status_check', 'notification_status_check')
                  AND pg_get_constraintdef(oid) LIKE '%SUPPRESSED%'
                """).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT indexname FROM pg_indexes
                WHERE indexname IN ('ix_notification_src_event', 'ix_delivery_dedup') ORDER BY indexname
                """).query(String.class).list()).containsExactly("ix_delivery_dedup", "ix_notification_src_event");
    }
}

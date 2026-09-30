package com.nms;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class MigrationIT extends IntegrationTestBase {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void migrationsApplyAndSeedDataIsPresent() {
        assertThat(jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success").query(Integer.class).single())
                .isEqualTo(4);
        assertThat(jdbc.sql("SELECT source_system FROM api_client ORDER BY source_system").query(String.class).list())
                .containsExactly("billing", "legacy", "trading");
        assertThat(jdbc.sql("SELECT count(*) FROM recipient").query(Integer.class).single()).isEqualTo(12);
        assertThat(jdbc.sql("SELECT count(*) FROM recipient_channel").query(Integer.class).single()).isEqualTo(18);
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

    @Test
    void v4AllowsWebhookChannelAndSeedsWebhookRecipient() {
        assertThat(jdbc.sql("SELECT channel || ' ' || address FROM recipient_channel WHERE recipient_id = 'cust-3001' "
                + "ORDER BY channel").query(String.class).list())
                .containsExactly("EMAIL integrations@example.com", "WEBHOOK http://localhost:9099/hooks/cust-3001");

        tx.executeWithoutResult(status -> {
            assertThat(insertDelivery(insertNotification(), "WEBHOOK")).isEqualTo(1);
            status.setRollbackOnly();
        });
        tx.executeWithoutResult(status -> {
            assertThatThrownBy(() -> jdbc.sql("INSERT INTO recipient_channel (recipient_id, channel, address, opted_out) "
                    + "VALUES ('cust-1002', 'FAX', 'x', FALSE)").update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("recipient_channel_channel_check");
            status.setRollbackOnly();
        });
        tx.executeWithoutResult(status -> {
            UUID notification = insertNotification();
            assertThatThrownBy(() -> insertDelivery(notification, "FAX"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("delivery_channel_check");
            status.setRollbackOnly();
        });
    }

    private UUID insertNotification() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO notification (id, source_system, event_id, type, severity, priority, priority_rank,
                    subject, body, body_hash, status, created_at, updated_at)
                VALUES (?, 'billing', 'e', 'ALERT', 'LOW', 'LOW', 0, 's', 'b', repeat('0', 64), 'ACCEPTED', now(), now())
                """).param(id).update();
        return id;
    }

    private int insertDelivery(UUID notificationId, String channel) {
        return jdbc.sql("""
                INSERT INTO delivery (id, notification_id, recipient_id, channel, address_masked, status, created_at)
                VALUES (?, ?, 'cust-3001', ?, '***', 'PENDING', now())
                """).param(UUID.randomUUID()).param(notificationId).param(channel).update();
    }
}

package com.nms.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nms.IntegrationTestBase;
import com.nms.common.domain.AuditEventType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class AuditServiceIT extends IntegrationTestBase {

    @Autowired
    AuditService audit;

    @Autowired
    AuditRepository auditRepository;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void recordRequiresCallerTransaction() {
        assertThatThrownBy(() -> audit.record(null, null, "billing", AuditEventType.NOTIFICATION_ACCEPTED, null,
                AuditDetails.none()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void eventsRollBackWithTheCallerAndAreReadInInsertionOrder() {
        UUID id = insertNotification("billing");
        tx.executeWithoutResult(s -> {
            audit.record(id, null, "billing", AuditEventType.NOTIFICATION_ACCEPTED, null, AuditDetails.none());
            audit.record(id, null, "billing", AuditEventType.ROUTING_DECIDED, null, AuditDetails.none());
        });
        tx.executeWithoutResult(s -> {
            audit.record(id, null, "billing", AuditEventType.DELIVERY_QUEUED, null, AuditDetails.none());
            s.setRollbackOnly();
        });

        List<AuditEvent> events = auditRepository.findForNotification(id, "billing");
        assertThat(events).extracting(AuditEvent::eventType)
                .containsExactly("NOTIFICATION_ACCEPTED", "ROUTING_DECIDED");
        assertThat(auditRepository.findForNotification(id, "trading")).isEmpty();
    }

    @Test
    void rejectionIsRecordedEvenWhenCallerRollsBack() {
        String marker = "rejection-" + UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            audit.recordRejection("billing", marker, AuditDetails.create().invalidFields(List.of("recipients")).build());
            s.setRollbackOnly();
        });
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE reason_code = :r AND notification_id IS NULL")
                .param("r", marker).query(Integer.class).single()).isEqualTo(1);
    }

    private UUID insertNotification(String sourceSystem) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO notification (id, source_system, event_id, type, severity, priority, priority_rank,
                    subject, body, body_hash, status, created_at, updated_at)
                VALUES (:id, :ss, 'e', 'ALERT', 'LOW', 'LOW', 0, 's', 'b', repeat('0', 64), 'ACCEPTED', now(), now())
                """).param("id", id).param("ss", sourceSystem).update();
        return id;
    }
}

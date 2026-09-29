package com.nms.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nms.common.domain.AuditEventType;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only audit writer. There are deliberately no update or delete operations.
 */
@Service
public class AuditService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    AuditService(JdbcClient jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Records an event in the caller's transaction, so it commits or rolls back with the state change. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            UUID notificationId,
            UUID deliveryId,
            String sourceSystem,
            AuditEventType type,
            String reasonCode,
            Map<String, Object> details) {
        insert(notificationId, deliveryId, sourceSystem, type, reasonCode, details);
    }

    /**
     * Records a {@code NOTIFICATION_REJECTED} event in its own transaction, for requests that never produced a
     * notification (or whose intake transaction was rolled back).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejection(String sourceSystem, String reasonCode, Map<String, Object> details) {
        insert(null, null, sourceSystem, AuditEventType.NOTIFICATION_REJECTED, reasonCode, details);
    }

    private void insert(
            UUID notificationId,
            UUID deliveryId,
            String sourceSystem,
            AuditEventType type,
            String reasonCode,
            Map<String, Object> details) {
        jdbc.sql("""
                INSERT INTO audit_event
                    (notification_id, delivery_id, source_system, event_type, reason_code, details, occurred_at)
                VALUES (:notificationId, :deliveryId, :sourceSystem, :type, :reason, CAST(:details AS jsonb), :at)
                """)
                .param("notificationId", notificationId)
                .param("deliveryId", deliveryId)
                .param("sourceSystem", sourceSystem)
                .param("type", type.name())
                .param("reason", reasonCode)
                .param("details", toJson(details))
                .param("at", Timestamp.from(clock.instant()))
                .update();
    }

    private String toJson(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Audit details must be serializable", e);
        }
    }
}

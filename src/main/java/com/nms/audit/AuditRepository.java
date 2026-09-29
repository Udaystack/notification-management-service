package com.nms.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads a notification's audit history, oldest first, visible only to the owning source system. */
@Repository
public class AuditRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    AuditRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<AuditEvent> findForNotification(UUID notificationId, String sourceSystem) {
        return jdbc.sql("""
                SELECT a.id, a.notification_id, a.delivery_id, a.event_type, a.reason_code,
                       a.details::text AS details, a.occurred_at
                FROM audit_event a
                JOIN notification n ON n.id = a.notification_id
                WHERE a.notification_id = :id AND n.source_system = :sourceSystem
                ORDER BY a.id
                """)
                .param("id", notificationId)
                .param("sourceSystem", sourceSystem)
                .query((rs, row) -> new AuditEvent(
                        rs.getLong("id"),
                        rs.getObject("notification_id", UUID.class),
                        rs.getObject("delivery_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("reason_code"),
                        parse(rs.getString("details")),
                        rs.getTimestamp("occurred_at").toInstant()))
                .list();
    }

    private com.fasterxml.jackson.databind.JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored audit details are not valid JSON", e);
        }
    }
}

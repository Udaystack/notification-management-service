package com.nms.api;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read side of the status API; every query is scoped to the owning source system. */
@Repository
class NotificationQueryRepository {

    private final JdbcClient jdbc;

    NotificationQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<NotificationView> find(UUID id, String sourceSystem) {
        List<NotificationView.DeliveryView> deliveries = jdbc.sql("""
                SELECT d.id, d.recipient_id, d.channel, d.address_masked, d.status, d.attempt_count,
                       d.last_failure_class, d.suppressed_by, o.notification_id AS suppressed_by_notification,
                       d.last_attempt_at, d.next_attempt_at, d.completed_at
                FROM delivery d LEFT JOIN delivery o ON o.id = d.suppressed_by
                WHERE d.notification_id = :id
                ORDER BY d.created_at, d.recipient_id, d.channel
                """)
                .param("id", id)
                .query((rs, row) -> new NotificationView.DeliveryView(
                        rs.getObject("id", UUID.class),
                        rs.getString("recipient_id"),
                        rs.getString("channel"),
                        rs.getString("address_masked"),
                        rs.getString("status"),
                        rs.getInt("attempt_count"),
                        rs.getString("last_failure_class"),
                        suppressedBy(rs),
                        instant(rs, "last_attempt_at"),
                        instant(rs, "next_attempt_at"),
                        instant(rs, "completed_at")))
                .list();
        List<String> channels = deliveries.stream().map(NotificationView.DeliveryView::channel).distinct().toList();
        return jdbc.sql("""
                SELECT id, event_id, type, severity, priority, status, created_at, scheduled_at, expires_at
                FROM notification WHERE id = :id AND source_system = :sourceSystem
                """)
                .param("id", id)
                .param("sourceSystem", sourceSystem)
                .query((rs, row) -> new NotificationView(
                        rs.getObject("id", UUID.class),
                        rs.getString("event_id"),
                        rs.getString("type"),
                        rs.getString("severity"),
                        rs.getString("priority"),
                        rs.getString("status"),
                        channels,
                        instant(rs, "created_at"),
                        instant(rs, "scheduled_at"),
                        instant(rs, "expires_at"),
                        deliveries))
                .optional();
    }

    boolean exists(UUID id, String sourceSystem) {
        return jdbc.sql("SELECT count(*) FROM notification WHERE id = :id AND source_system = :sourceSystem")
                .param("id", id)
                .param("sourceSystem", sourceSystem)
                .query(Integer.class)
                .single() > 0;
    }

    private static NotificationView.SuppressedBy suppressedBy(ResultSet rs) throws SQLException {
        UUID deliveryId = rs.getObject("suppressed_by", UUID.class);
        return deliveryId == null ? null
                : new NotificationView.SuppressedBy(rs.getObject("suppressed_by_notification", UUID.class), deliveryId);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}

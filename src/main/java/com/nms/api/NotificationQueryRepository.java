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
                SELECT id, recipient_id, channel, address_masked, status, attempt_count, last_failure_class,
                       last_attempt_at, next_attempt_at, completed_at
                FROM delivery WHERE notification_id = :id
                ORDER BY created_at, recipient_id, channel
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

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}

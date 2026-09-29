package com.nms.intake;

import com.nms.common.Hashing;
import com.nms.common.domain.NotificationStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {

    private final JdbcClient jdbc;

    NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts unless {@code (sourceSystem, idempotencyKey)} already exists. Uses {@code ON CONFLICT DO NOTHING} so
     * the transaction stays usable when the key is taken.
     *
     * @return {@code true} if inserted
     */
    boolean insertIfAbsent(UUID id, SubmitCommand command, String idempotencyKey, String requestHash, Instant now) {
        return jdbc.sql("""
                INSERT INTO notification (id, source_system, idempotency_key, request_hash, event_id, type, severity,
                    priority, priority_rank, subject, body, body_hash, status, scheduled_at, expires_at,
                    created_at, updated_at)
                VALUES (:id, :sourceSystem, :key, :hash, :eventId, :type, :severity, :priority, :rank, :subject,
                    :body, :bodyHash, :status, :scheduledAt, :expiresAt, :now, :now)
                ON CONFLICT (source_system, idempotency_key) DO NOTHING
                """)
                .param("id", id)
                .param("sourceSystem", command.sourceSystem())
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .param("eventId", command.eventId())
                .param("type", command.type().name())
                .param("severity", command.severity().name())
                .param("priority", command.priority().name())
                .param("rank", command.priority().rank())
                .param("subject", command.subject())
                .param("body", command.body())
                .param("bodyHash", Hashing.sha256Hex(command.body()))
                .param("status", NotificationStatus.ACCEPTED.name())
                .param("scheduledAt", timestamp(command.scheduledAt()))
                .param("expiresAt", timestamp(command.expiresAt()))
                .param("now", timestamp(now))
                .update() == 1;
    }

    Optional<StoredNotification> findByIdempotencyKey(String sourceSystem, String idempotencyKey) {
        return jdbc.sql("""
                SELECT id, request_hash, status FROM notification
                WHERE source_system = :sourceSystem AND idempotency_key = :key
                """)
                .param("sourceSystem", sourceSystem)
                .param("key", idempotencyKey)
                .query((rs, row) -> new StoredNotification(
                        rs.getObject("id", UUID.class),
                        rs.getString("request_hash"),
                        NotificationStatus.valueOf(rs.getString("status"))))
                .optional();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}

package com.nms.delivery;

import com.nms.audit.AuditDetails;
import com.nms.audit.AuditService;
import com.nms.common.config.NmsProperties;
import com.nms.common.domain.AuditEventType;
import com.nms.common.domain.Channel;
import com.nms.common.domain.DeliveryStatus;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims deliveries with {@code FOR UPDATE SKIP LOCKED} in one short transaction, so concurrent workers (and
 * instances) never claim the same row. A claim counts as an attempt.
 */
@Component
class PostgresDeliveryQueue implements DeliveryQueue {

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final NotificationStatusUpdater statusUpdater;
    private final DeliveryMetrics metrics;
    private final NmsProperties properties;
    private final Clock clock;

    PostgresDeliveryQueue(JdbcClient jdbc, AuditService audit, NotificationStatusUpdater statusUpdater,
            DeliveryMetrics metrics, NmsProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.statusUpdater = statusUpdater;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    private record Due(UUID id, UUID notificationId, String sourceSystem, String eventId, String type,
            String severity, String priority, String recipientId, Channel channel, DeliveryStatus status,
            int attemptCount, String subject, String body, Instant expiresAt) {}

    @Override
    @Transactional
    public List<ClaimedDelivery> claim(int limit) {
        Instant now = clock.instant();
        List<Due> due = jdbc.sql("""
                SELECT d.id, d.notification_id, n.source_system, n.event_id, n.type, n.severity, n.priority,
                       d.recipient_id, d.channel, d.status, d.attempt_count, n.subject, n.body, n.expires_at
                FROM delivery d JOIN notification n ON n.id = d.notification_id
                WHERE (d.status IN ('PENDING', 'RETRY_SCHEDULED') AND d.next_attempt_at <= :now)
                   OR (d.status = 'IN_FLIGHT' AND d.locked_until < :now)
                ORDER BY n.priority_rank DESC, d.next_attempt_at
                LIMIT :limit
                FOR UPDATE OF d SKIP LOCKED
                """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query((rs, row) -> new Due(
                        rs.getObject("id", UUID.class),
                        rs.getObject("notification_id", UUID.class),
                        rs.getString("source_system"),
                        rs.getString("event_id"),
                        rs.getString("type"),
                        rs.getString("severity"),
                        rs.getString("priority"),
                        rs.getString("recipient_id"),
                        Channel.valueOf(rs.getString("channel")),
                        DeliveryStatus.valueOf(rs.getString("status")),
                        rs.getInt("attempt_count"),
                        rs.getString("subject"),
                        rs.getString("body"),
                        rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant()))
                .list();

        List<ClaimedDelivery> claimed = new ArrayList<>();
        Set<UUID> touched = new LinkedHashSet<>();
        for (Due d : due) {
            touched.add(d.notificationId());
            if (d.expiresAt() != null && now.isAfter(d.expiresAt())) {
                expire(d, now);
            } else {
                claimed.add(markInFlight(d, now));
            }
        }
        touched.forEach(id -> statusUpdater.recompute(id, now));
        return claimed;
    }

    private void expire(Due d, Instant now) {
        DeliveryStateMachine.transition(d.status(), DeliveryStatus.EXPIRED);
        jdbc.sql("""
                UPDATE delivery SET status = 'EXPIRED', completed_at = :now, locked_until = NULL,
                    next_attempt_at = NULL, version = version + 1
                WHERE id = :id
                """)
                .param("now", Timestamp.from(now))
                .param("id", d.id())
                .update();
        audit.record(d.notificationId(), d.id(), d.sourceSystem(), AuditEventType.DELIVERY_EXPIRED, null,
                AuditDetails.create().recipientId(d.recipientId()).channel(d.channel()).build());
        metrics.expired(d.channel());
    }

    private ClaimedDelivery markInFlight(Due d, Instant now) {
        DeliveryStateMachine.transition(d.status(), DeliveryStatus.IN_FLIGHT);
        long version = jdbc.sql("""
                UPDATE delivery SET status = 'IN_FLIGHT', locked_until = :lockedUntil,
                    attempt_count = attempt_count + 1, last_attempt_at = :now, next_attempt_at = NULL,
                    version = version + 1
                WHERE id = :id
                RETURNING version
                """)
                .param("lockedUntil", Timestamp.from(now.plus(properties.worker().leaseDuration())))
                .param("now", Timestamp.from(now))
                .param("id", d.id())
                .query(Long.class)
                .single();
        int attempt = d.attemptCount() + 1;
        audit.record(d.notificationId(), d.id(), d.sourceSystem(), AuditEventType.DELIVERY_ATTEMPTED, null,
                AuditDetails.create().recipientId(d.recipientId()).channel(d.channel()).attempt(attempt).build());
        return new ClaimedDelivery(d.id(), d.notificationId(), d.sourceSystem(), d.eventId(), d.type(), d.severity(),
                d.priority(), d.recipientId(), d.channel(), attempt, version, d.subject(), d.body(), d.expiresAt());
    }
}

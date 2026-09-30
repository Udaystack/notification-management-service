package com.nms.delivery;

import java.time.Duration;
import java.util.UUID;

/** Seeds due deliveries with an exact priority and due time, so claim order can be asserted. */
abstract class PriorityTestSupport extends DeliveryTestSupport {

    private static int rank(String priority) {
        return switch (priority) {
            case "LOW" -> 0;
            case "NORMAL" -> 1;
            case "HIGH" -> 2;
            default -> throw new IllegalArgumentException(priority);
        };
    }

    private UUID notification(String priority, String expiresAtSql) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO notification (id, source_system, event_id, type, severity, priority, priority_rank, "
                + "subject, body, body_hash, status, created_at, updated_at, expires_at) "
                + "VALUES (?, 'billing', ?, 'TRANSACTIONAL', 'MEDIUM', ?, ?, 's', 'b', repeat('0', 64), 'ACCEPTED', "
                + "now(), now(), " + expiresAtSql + ")")
                .param(id).param("EVT-" + id).param(priority).param(rank(priority)).update();
        return id;
    }

    private UUID delivery(UUID notificationId, String status, int attempts, Duration dueAgo, Duration leaseExpiredAgo) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO delivery (id, notification_id, recipient_id, channel, address_masked, status,
                    attempt_count, next_attempt_at, locked_until, created_at)
                VALUES (?, ?, 'cust-1001', 'EMAIL', 'j***@example.com', ?, ?,
                    now() - make_interval(secs => ?), now() - make_interval(secs => ?), now())
                """)
                .param(id).param(notificationId).param(status).param(attempts)
                .param(dueAgo == null ? null : (double) dueAgo.toSeconds())
                .param(leaseExpiredAgo == null ? null : (double) leaseExpiredAgo.toSeconds())
                .update();
        return id;
    }

    /** A first attempt ({@code PENDING}, never attempted) due {@code dueAgo} ago. */
    protected UUID pending(String priority, Duration dueAgo) {
        return delivery(notification(priority, "NULL"), "PENDING", 0, dueAgo, null);
    }

    /** A retry ({@code RETRY_SCHEDULED} after one attempt) due {@code dueAgo} ago. */
    protected UUID retry(String priority, Duration dueAgo) {
        return delivery(notification(priority, "NULL"), "RETRY_SCHEDULED", 1, dueAgo, null);
    }

    /** A reclaimable {@code IN_FLIGHT} delivery whose lease expired {@code leaseExpiredAgo} ago. */
    protected UUID reclaimable(String priority, Duration leaseExpiredAgo) {
        return delivery(notification(priority, "NULL"), "IN_FLIGHT", 1, null, leaseExpiredAgo);
    }

    /** A never-attempted {@code PENDING} delivery whose notification has already expired. */
    protected UUID pendingExpired(String priority) {
        return delivery(notification(priority, "now() - interval '1 second'"), "PENDING", 0, Duration.ofMinutes(1), null);
    }

    protected String storedPriority(UUID deliveryId) {
        return jdbc.sql("SELECT n.priority || '/' || n.priority_rank FROM notification n "
                + "JOIN delivery d ON d.notification_id = n.id WHERE d.id = ?").param(deliveryId)
                .query(String.class).single();
    }
}

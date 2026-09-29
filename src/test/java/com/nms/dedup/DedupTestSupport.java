package com.nms.dedup;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nms.api.ApiTestSupport;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Helpers for event-deduplication tests. Subclasses set {@code nms.dedup.enabled} themselves so they pass whether
 * the suite runs with the flag on or off.
 */
abstract class DedupTestSupport extends ApiTestSupport {

    protected ObjectNode request(String sourceSystem, String eventId, List<String> channels, String... recipients) {
        ObjectNode body = request(sourceSystem, recipients).put("eventId", eventId);
        var list = body.putArray("channels");
        channels.forEach(list::add);
        return body;
    }

    protected static String newEventId() {
        return "EVT-" + UUID.randomUUID();
    }

    protected record Row(UUID id, String recipientId, String channel, String status, UUID suppressedBy) {}

    protected List<Row> deliveries(UUID notificationId) {
        return jdbc.sql("""
                SELECT id, recipient_id, channel, status, suppressed_by FROM delivery
                WHERE notification_id = ? ORDER BY recipient_id, channel
                """).param(notificationId)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("recipient_id"),
                        rs.getString("channel"), rs.getString("status"), rs.getObject("suppressed_by", UUID.class)))
                .list();
    }

    protected Row onlyDelivery(UUID notificationId) {
        List<Row> rows = deliveries(notificationId);
        if (rows.size() != 1) {
            throw new AssertionError("expected one delivery, got " + rows);
        }
        return rows.get(0);
    }

    /** Moves a notification and its deliveries back in time, as if submitted {@code age} ago. */
    protected void backdate(UUID notificationId, Duration age) {
        jdbc.sql("UPDATE delivery SET created_at = created_at - make_interval(secs => ?) WHERE notification_id = ?")
                .param(age.toSeconds()).param(notificationId).update();
        jdbc.sql("UPDATE notification SET created_at = created_at - make_interval(secs => ?) WHERE id = ?")
                .param(age.toSeconds()).param(notificationId).update();
    }

    protected String notificationStatus(UUID notificationId) {
        return jdbc.sql("SELECT status FROM notification WHERE id = ?").param(notificationId)
                .query(String.class).single();
    }

    /** Statuses of every billing EMAIL delivery to cust-1001 for the request's event ID. */
    protected List<String> emailStatuses(ObjectNode body) {
        return jdbc.sql("""
                SELECT d.status FROM delivery d JOIN notification n ON n.id = d.notification_id
                WHERE n.source_system = 'billing' AND n.event_id = ? AND d.recipient_id = 'cust-1001'
                  AND d.channel = 'EMAIL'
                ORDER BY d.status
                """).param(body.get("eventId").asText()).query(String.class).list();
    }
}

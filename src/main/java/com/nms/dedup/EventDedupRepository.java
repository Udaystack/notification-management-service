package com.nms.dedup;

import com.nms.common.domain.Channel;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class EventDedupRepository {

    private final JdbcClient jdbc;

    EventDedupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Serializes submissions of the same dedup key until the caller's transaction ends. Uses a 64-bit hash; a
     * collision only makes two unrelated keys wait on each other, because the lookup compares full keys.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String dedupKey) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", dedupKey)
                .query((rs, row) -> null)
                .list();
    }

    /**
     * The oldest delivery of the same event, recipient, and channel created since {@code since} that is not
     * {@code FAILED}, {@code EXPIRED}, or {@code SUPPRESSED}.
     */
    public Optional<OriginalDelivery> findOriginal(
            String sourceSystem, String eventId, String recipientId, Channel channel, Instant since) {
        return jdbc.sql("""
                SELECT d.id, d.notification_id FROM delivery d
                JOIN notification n ON n.id = d.notification_id
                WHERE n.source_system = :src AND n.event_id = :evt
                  AND d.recipient_id = :rcp AND d.channel = :ch
                  AND d.created_at >= :since
                  AND d.status NOT IN ('FAILED', 'EXPIRED', 'SUPPRESSED')
                ORDER BY d.created_at
                LIMIT 1
                """)
                .param("src", sourceSystem)
                .param("evt", eventId)
                .param("rcp", recipientId)
                .param("ch", channel.name())
                .param("since", Timestamp.from(since))
                .query((rs, row) -> new OriginalDelivery(
                        rs.getObject("notification_id", UUID.class), rs.getObject("id", UUID.class)))
                .optional();
    }
}

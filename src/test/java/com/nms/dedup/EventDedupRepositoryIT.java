package com.nms.dedup;

import static org.assertj.core.api.Assertions.assertThat;

import com.nms.IntegrationTestBase;
import com.nms.common.domain.Channel;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class EventDedupRepositoryIT extends IntegrationTestBase {

    private static final Duration WINDOW = Duration.ofHours(24);

    @Autowired
    EventDedupRepository repository;

    @Autowired
    JdbcClient jdbc;

    private UUID notification(String sourceSystem, String eventId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO notification (id, source_system, event_id, type, severity, priority, priority_rank,
                    subject, body, body_hash, status, created_at, updated_at)
                VALUES (:id, :src, :evt, 'ALERT', 'LOW', 'NORMAL', 1, 's', 'b', repeat('0', 64), 'ACCEPTED',
                    now(), now())
                """).param("id", id).param("src", sourceSystem).param("evt", eventId).update();
        return id;
    }

    private UUID delivery(UUID notificationId, String recipient, Channel channel, String status, Duration age) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO delivery (id, notification_id, recipient_id, channel, address_masked, status, created_at)
                VALUES (:id, :n, :r, :ch, '***', :status, now() - make_interval(secs => :age))
                """).param("id", id).param("n", notificationId).param("r", recipient).param("ch", channel.name())
                .param("status", status).param("age", age.toSeconds()).update();
        return id;
    }

    private java.util.Optional<OriginalDelivery> find(String src, String evt, Channel channel) {
        return repository.findOriginal(src, evt, "cust-1001", channel, Instant.now().minus(WINDOW));
    }

    @Test
    void findsTheOldestDeliverableDeliveryOfTheSameKey() {
        String evt = "evt-" + UUID.randomUUID();
        UUID n1 = notification("billing", evt);
        UUID older = delivery(n1, "cust-1001", Channel.EMAIL, "SENT", Duration.ofHours(2));
        UUID n2 = notification("billing", evt);
        delivery(n2, "cust-1001", Channel.EMAIL, "PENDING", Duration.ofHours(1));

        assertThat(find("billing", evt, Channel.EMAIL)).contains(new OriginalDelivery(n1, older));
    }

    @Test
    void otherChannelAndOtherSourceSystemDoNotMatch() {
        String evt = "evt-" + UUID.randomUUID();
        delivery(notification("billing", evt), "cust-1001", Channel.EMAIL, "SENT", Duration.ofMinutes(5));

        assertThat(find("billing", evt, Channel.SMS)).isEmpty();
        assertThat(find("trading", evt, Channel.EMAIL)).isEmpty();
    }

    @Test
    void failedExpiredAndSuppressedDeliveriesAreExcluded() {
        String evt = "evt-" + UUID.randomUUID();
        UUID n = notification("billing", evt);
        delivery(n, "cust-1001", Channel.EMAIL, "FAILED", Duration.ofMinutes(5));
        delivery(notification("billing", evt), "cust-1001", Channel.EMAIL, "EXPIRED", Duration.ofMinutes(4));
        delivery(notification("billing", evt), "cust-1001", Channel.EMAIL, "SUPPRESSED", Duration.ofMinutes(3));

        assertThat(find("billing", evt, Channel.EMAIL)).isEmpty();
    }

    @Test
    void deliveriesOutsideTheWindowAreIgnored() {
        String evt = "evt-" + UUID.randomUUID();
        delivery(notification("billing", evt), "cust-1001", Channel.EMAIL, "SENT", Duration.ofHours(25));

        assertThat(find("billing", evt, Channel.EMAIL)).isEmpty();
    }
}

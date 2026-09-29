package com.nms.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nms.delivery.DeliveryMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** The event-deduplication scenarios with the feature on (default window of 24 hours). */
@TestPropertySource(properties = {"nms.dedup.enabled=true", "nms.dedup.window=24h"})
class EventDedupIT extends DedupTestSupport {

    @Autowired
    MeterRegistry meters;

    @Test
    void sameEventResubmittedWithANewKey() throws Exception {
        String eventId = newEventId();
        ObjectNode body = request("billing", eventId, List.of("EMAIL"), "cust-1001");
        UUID first = idOf(submit(BILLING_KEY, newKey(), body).andExpect(status().isAccepted()));
        UUID second = idOf(submit(BILLING_KEY, newKey(), body)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SUPPRESSED")));

        assertThat(second).isNotEqualTo(first);
        Row original = onlyDelivery(first);
        Row duplicate = onlyDelivery(second);
        assertThat(original.status()).isEqualTo("PENDING");
        assertThat(duplicate.status()).isEqualTo("SUPPRESSED");
        assertThat(duplicate.suppressedBy()).isEqualTo(original.id());
        assertThat(notificationStatus(second)).isEqualTo("SUPPRESSED");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit_event
                WHERE delivery_id = ? AND event_type = 'DELIVERY_SUPPRESSED' AND reason_code = 'DUPLICATE_EVENT'
                """).param(duplicate.id()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void suppressionIsAuditable() throws Exception {
        String eventId = newEventId();
        ObjectNode body = request("billing", eventId, List.of("EMAIL"), "cust-1001");
        UUID first = idOf(submit(BILLING_KEY, newKey(), body));
        Counter counter = meters.counter(DeliveryMetrics.SUPPRESSED, "channel", "EMAIL", "sourceSystem", "billing");
        double before = counter.count();

        UUID second = idOf(submit(BILLING_KEY, newKey(), body));

        Row original = onlyDelivery(first);
        Row duplicate = onlyDelivery(second);
        List<String> types = jdbc.sql("SELECT event_type FROM audit_event WHERE notification_id = ? ORDER BY id")
                .param(second).query(String.class).list();
        assertThat(types).containsExactly("NOTIFICATION_ACCEPTED", "ROUTING_DECIDED", "DELIVERY_SUPPRESSED");

        JsonNode details = mapper.readTree(jdbc.sql("""
                SELECT details::text FROM audit_event WHERE delivery_id = ? AND event_type = 'DELIVERY_SUPPRESSED'
                """).param(duplicate.id()).query(String.class).single());
        assertThat(details.get("originalDeliveryId").asText()).isEqualTo(original.id().toString());
        assertThat(details.get("originalNotificationId").asText()).isEqualTo(first.toString());
        assertThat(details.get("recipientId").asText()).isEqualTo("cust-1001");
        assertThat(details.get("channel").asText()).isEqualTo("EMAIL");
        assertThat(details.get("address").asText()).isEqualTo("j***@example.com");
        assertThat(counter.count() - before).isEqualTo(1.0);
    }

    @Test
    void partialOverlap() throws Exception {
        String eventId = newEventId();
        submit(BILLING_KEY, newKey(), request("billing", eventId, List.of("EMAIL"), "cust-1001"));
        UUID second = idOf(submit(BILLING_KEY, newKey(),
                request("billing", eventId, List.of("EMAIL"), "cust-1001", "cust-1002"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED")));

        List<Row> rows = deliveries(second);
        assertThat(rows).extracting(Row::recipientId, Row::status)
                .containsExactly(Tuple.tuple("cust-1001", "SUPPRESSED"),
                        Tuple.tuple("cust-1002", "PENDING"));
        assertThat(rows.get(1).suppressedBy()).isNull();
    }

    @Test
    void differentChannelIsNotADuplicate() throws Exception {
        String eventId = newEventId();
        submit(BILLING_KEY, newKey(), request("billing", eventId, List.of("EMAIL"), "cust-1001"));
        UUID second = idOf(submit(BILLING_KEY, newKey(), request("billing", eventId, List.of("SMS"), "cust-1001")));

        Row sms = onlyDelivery(second);
        assertThat(sms.channel()).isEqualTo("SMS");
        assertThat(sms.status()).isEqualTo("PENDING");
        assertThat(sms.suppressedBy()).isNull();
    }

    @Test
    void outsideTheWindow() throws Exception {
        String eventId = newEventId();
        ObjectNode body = request("billing", eventId, List.of("EMAIL"), "cust-1001");
        UUID first = idOf(submit(BILLING_KEY, newKey(), body));
        backdate(first, Duration.ofHours(25));

        UUID second = idOf(submit(BILLING_KEY, newKey(), body));

        assertThat(onlyDelivery(second).status()).isEqualTo("PENDING");
    }

    @Test
    void originalDeliveryFailed() throws Exception {
        String eventId = newEventId();
        ObjectNode body = request("billing", eventId, List.of("EMAIL"), "cust-1001");
        UUID first = idOf(submit(BILLING_KEY, newKey(), body));
        jdbc.sql("UPDATE delivery SET status = 'FAILED', next_attempt_at = NULL, completed_at = now() "
                + "WHERE notification_id = ?").param(first).update();

        UUID second = idOf(submit(BILLING_KEY, newKey(), body));

        assertThat(onlyDelivery(second).status()).isEqualTo("PENDING");
    }

    @Test
    void differentSourceSystems() throws Exception {
        String eventId = newEventId();
        UUID billing = idOf(submit(BILLING_KEY, newKey(), request("billing", eventId, List.of("EMAIL"), "cust-1001")));
        UUID trading = idOf(submit(TRADING_KEY, newKey(), request("trading", eventId, List.of("EMAIL"), "cust-1001")));

        assertThat(onlyDelivery(billing).status()).isEqualTo("PENDING");
        assertThat(onlyDelivery(trading).status()).isEqualTo("PENDING");
    }

    @Test
    void concurrentDuplicateEvents() throws Exception {
        String eventId = newEventId();
        String json = mapper.writeValueAsString(request("billing", eventId, List.of("EMAIL"), "cust-1001"));
        int submissions = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<UUID>> tasks = new ArrayList<>();
        for (int i = 0; i < submissions; i++) {
            tasks.add(() -> {
                start.await();
                return idOf(submit(BILLING_KEY, newKey(), json).andExpect(status().isAccepted()));
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(submissions);
        try {
            List<Future<UUID>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            List<Row> rows = new ArrayList<>();
            for (Future<UUID> future : futures) {
                rows.add(onlyDelivery(future.get()));
            }
            List<Row> deliverable = rows.stream().filter(r -> r.status().equals("PENDING")).toList();
            assertThat(deliverable).hasSize(1);
            assertThat(rows).filteredOn(r -> r.status().equals("SUPPRESSED")).hasSize(submissions - 1)
                    .allSatisfy(r -> assertThat(r.suppressedBy()).isEqualTo(deliverable.get(0).id()));
        } finally {
            pool.shutdownNow();
        }
    }
}

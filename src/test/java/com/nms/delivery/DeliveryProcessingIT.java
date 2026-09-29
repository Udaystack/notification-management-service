package com.nms.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nms.channel.DeliveryRequest;
import com.nms.channel.SimulatedProvider;
import com.nms.common.domain.Channel;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

class DeliveryProcessingIT extends DeliveryTestSupport {

    @Autowired
    @Qualifier("emailProvider")
    SimulatedProvider emailProvider;

    @Test
    void successfulDelivery() throws Exception {
        UUID id = submitTo("cust-1001");
        assertThat(worker.runOnce(10)).isEqualTo(1);

        assertThat(deliveryStatus(id)).isEqualTo("SENT");
        assertThat(jdbc.sql("SELECT completed_at FROM delivery WHERE id = ?").param(id)
                .query(Timestamp.class).optional()).isPresent();
        assertThat(auditTypes(id)).containsSubsequence("DELIVERY_ATTEMPTED", "DELIVERY_SUCCEEDED");
    }

    @Test
    void priorityOrdering() throws Exception {
        UUID low = submitAndGetDelivery(request("billing", "cust-1001").put("priority", "LOW"));
        UUID high = submitAndGetDelivery(request("billing", "cust-1001").put("priority", "HIGH"));

        List<ClaimedDelivery> first = worker.claim(1);
        assertThat(first).extracting(ClaimedDelivery::id).containsExactly(high);
        assertThat(worker.claim(1)).extracting(ClaimedDelivery::id).containsExactly(low);
    }

    @Test
    void twoWorkersRaceForOneDelivery() throws Exception {
        Set<UUID> submitted = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            submitted.add(submitTo("cust-1001"));
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<List<ClaimedDelivery>>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit((Callable<List<ClaimedDelivery>>) () -> {
                    start.await();
                    return worker.claim(10);
                }));
            }
            start.countDown();
            List<UUID> claimed = new ArrayList<>();
            for (Future<List<ClaimedDelivery>> f : results) {
                f.get().forEach(c -> claimed.add(c.id()));
            }
            assertThat(claimed).doesNotHaveDuplicates();
            assertThat(new HashSet<>(claimed)).isEqualTo(submitted);
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "cust-2001, RETRY_SCHEDULED, TRANSIENT",
        "cust-2002, RETRY_SCHEDULED, TIMEOUT",
        "cust-2003, RETRY_SCHEDULED, RATE_LIMITED",
        "cust-2004, FAILED, PERMANENT_REJECTION",
        "cust-2005, FAILED, INVALID_RECIPIENT",
        "cust-2006, FAILED, AUTH_ERROR"
    })
    void eachFailureClassViaMarkers(String recipient, String expectedStatus, String failureClass) throws Exception {
        UUID id = submitTo(recipient);
        worker.runOnce(10);

        assertThat(deliveryStatus(id)).isEqualTo(expectedStatus);
        assertThat(jdbc.sql("SELECT last_failure_class FROM delivery WHERE id = ?").param(id)
                .query(String.class).single()).isEqualTo(failureClass);
    }

    @Test
    void transientFailureThenSuccess() throws Exception {
        UUID id = submitTo("cust-2007");
        worker.runOnce(10);
        assertThat(deliveryStatus(id)).isEqualTo("RETRY_SCHEDULED");

        makeDue(id);
        worker.runOnce(10);
        assertThat(deliveryStatus(id)).isEqualTo("SENT");
        assertThat(attempts(id)).isEqualTo(2);
        assertThat(auditTypes(id)).containsSubsequence(
                "DELIVERY_ATTEMPTED", "RETRY_SCHEDULED", "DELIVERY_ATTEMPTED", "DELIVERY_SUCCEEDED");
    }

    @Test
    void retriesExhausted() throws Exception {
        UUID id = submitTo("cust-2002");
        for (int attempt = 1; attempt <= 5; attempt++) {
            makeDue(id);
            worker.runOnce(10);
        }
        assertThat(deliveryStatus(id)).isEqualTo("FAILED");
        assertThat(attempts(id)).isEqualTo(5);
        assertThat(auditReason(id, "DELIVERY_FAILED")).isEqualTo("RETRIES_EXHAUSTED");
    }

    @Test
    void permanentFailureIsNotRetried() throws Exception {
        UUID id = submitTo("cust-2005");
        worker.runOnce(10);

        assertThat(deliveryStatus(id)).isEqualTo("FAILED");
        assertThat(attempts(id)).isEqualTo(1);
        assertThat(auditTypes(id)).doesNotContain("RETRY_SCHEDULED");
        assertThat(auditReason(id, "DELIVERY_FAILED")).isEqualTo("INVALID_RECIPIENT");
    }

    @Test
    void rateLimitHonored() throws Exception {
        UUID id = submitTo("cust-2003");
        worker.runOnce(10);

        Timestamp last = jdbc.sql("SELECT last_attempt_at FROM delivery WHERE id = ?").param(id)
                .query(Timestamp.class).single();
        Timestamp next = jdbc.sql("SELECT next_attempt_at FROM delivery WHERE id = ?").param(id)
                .query(Timestamp.class).single();
        assertThat(Duration.between(last.toInstant(), next.toInstant())).isGreaterThanOrEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void expiresWhileWaitingToRetry() throws Exception {
        ObjectNode body = request("billing", "cust-2003").put("expiresAt", Instant.now().plusSeconds(10).toString());
        UUID id = submitAndGetDelivery(body);
        worker.runOnce(10);

        assertThat(deliveryStatus(id)).isEqualTo("EXPIRED");
        assertThat(auditTypes(id)).contains("DELIVERY_EXPIRED");
    }

    @Test
    void claimedAfterExpiry() throws Exception {
        ObjectNode body = request("billing", "cust-1001").put("expiresAt", Instant.now().plusSeconds(60).toString());
        UUID id = submitAndGetDelivery(body);
        jdbc.sql("""
                UPDATE notification SET expires_at = now() - interval '1 second'
                WHERE id = (SELECT notification_id FROM delivery WHERE id = ?)
                """).param(id).update();

        assertThat(worker.runOnce(10)).isZero();
        assertThat(deliveryStatus(id)).isEqualTo("EXPIRED");
        assertThat(attempts(id)).isZero();
        assertThat(emailProvider.sendCount(id)).isZero();
        assertThat(auditTypes(id)).contains("DELIVERY_EXPIRED").doesNotContain("DELIVERY_ATTEMPTED");
    }

    @Test
    void workerCrashesMidDelivery() throws Exception {
        UUID id = submitTo("cust-1001");
        ClaimedDelivery crashed = worker.claim(1).getFirst();
        // The crashed worker reached the provider but never recorded the outcome.
        emailProvider.send(new DeliveryRequest(crashed.id(), Channel.EMAIL, "jane.doe@example.com",
                crashed.subject(), crashed.body(), crashed.attempt()));
        assertThat(worker.claim(1)).as("lease still held").isEmpty();

        jdbc.sql("UPDATE delivery SET locked_until = now() - interval '1 second' WHERE id = ?").param(id).update();
        assertThat(worker.runOnce(1)).isEqualTo(1);

        assertThat(deliveryStatus(id)).isEqualTo("SENT");
        assertThat(attempts(id)).isEqualTo(2);
        assertThat(emailProvider.sendCount(id)).isEqualTo(1);
    }
}

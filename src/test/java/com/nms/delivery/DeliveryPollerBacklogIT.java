package com.nms.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nms.api.ApiTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * With a long poll interval and every slot used by each batch, a backlog of several batches must still be drained
 * within one poll
 * cycle: the poller waits only for free slots, never for the next interval.
 */
@TestPropertySource(properties = {
    "nms.worker.enabled=true", "nms.worker.poll-interval=4s", "nms.worker.batch-size=5", "nms.worker.concurrency=5"})
class DeliveryPollerBacklogIT extends ApiTestSupport {

    @Test
    void backlogIsDrainedWithoutWaitingForThePollInterval() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(idOf(submit(BILLING_KEY, newKey(), request("billing", "cust-1001")).andExpect(status().isAccepted())));
        }
        Instant firstSent = waitUntil(() -> count("SELECT count(*) FROM delivery WHERE status = 'SENT'") > 0);
        Instant allSent = waitUntil(() -> ids.stream().allMatch(id ->
                "COMPLETED".equals(jdbc.sql("SELECT status FROM notification WHERE id = ?").param(id)
                        .query(String.class).single())));

        // Four batches of 5; waiting for the interval between them would take at least 3 x 4s.
        assertThat(Duration.between(firstSent, allSent)).isLessThan(Duration.ofSeconds(3));
    }

    private Instant waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return Instant.now();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("condition not met within 30s");
    }
}

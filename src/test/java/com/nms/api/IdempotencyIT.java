package com.nms.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class IdempotencyIT extends ApiTestSupport {

    @Test
    void sameKeySamePayload() throws Exception {
        String key = newKey();
        ObjectNode body = request("billing", "cust-1001");
        UUID id = idOf(submit(BILLING_KEY, key, body).andExpect(status().isAccepted()));

        submit(BILLING_KEY, key, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        assertThat(count("SELECT count(*) FROM notification WHERE idempotency_key = ?", key)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM delivery WHERE notification_id = ?", id)).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM audit_event WHERE notification_id = ? AND event_type = 'DUPLICATE_SUBMISSION'
                """, id)).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentPayload() throws Exception {
        String key = newKey();
        UUID id = idOf(submit(BILLING_KEY, key, request("billing", "cust-1001")).andExpect(status().isAccepted()));
        int notifications = count("SELECT count(*) FROM notification");
        int originalAudit = count("SELECT count(*) FROM audit_event WHERE notification_id = ?", id);

        submit(BILLING_KEY, key, request("billing", "cust-1001").put("subject", "Different"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("IDEMPOTENCY_KEY_CONFLICT"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain(id.toString()));

        assertThat(count("SELECT count(*) FROM notification")).isEqualTo(notifications);
        assertThat(count("SELECT count(*) FROM audit_event WHERE notification_id = ?", id)).isEqualTo(originalAudit);
    }

    @Test
    void concurrentDuplicateSubmissions() throws Exception {
        String key = newKey();
        ObjectNode body = request("billing", "cust-1001");
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit((Callable<Integer>) () -> {
                    start.await();
                    return submit(BILLING_KEY, key, body).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsOnly(202, 200);
            assertThat(statuses).filteredOn(s -> s == 202).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM notification WHERE idempotency_key = ?", key)).isEqualTo(1);
    }

    @Test
    void sameKeyFromDifferentSourceSystems() throws Exception {
        String key = "abc-123-" + UUID.randomUUID();
        UUID billing = idOf(submit(BILLING_KEY, key, request("billing", "cust-1001")).andExpect(status().isAccepted()));
        UUID trading = idOf(submit(TRADING_KEY, key, request("trading", "cust-1001")).andExpect(status().isAccepted()));
        assertThat(billing).isNotEqualTo(trading);
    }
}

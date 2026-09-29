package com.nms.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nms.api.ApiTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"nms.worker.enabled=true", "nms.worker.poll-interval=100ms"})
class DeliveryPollerIT extends ApiTestSupport {

    @Test
    void submittedNotificationReachesSent() throws Exception {
        UUID id = idOf(submit(BILLING_KEY, newKey(), request("billing", "cust-1001")).andExpect(status().isAccepted()));

        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        String status = null;
        while (Instant.now().isBefore(deadline)) {
            status = jdbc.sql("SELECT status FROM notification WHERE id = ?").param(id).query(String.class).single();
            if (status.equals("COMPLETED")) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(status).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT status FROM delivery WHERE notification_id = ?").param(id)
                .query(String.class).single()).isEqualTo("SENT");
    }
}

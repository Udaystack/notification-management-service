package com.nms.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nms.intake.IdempotencyCleanupJob;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IdempotencyRetentionIT extends ApiTestSupport {

    @Autowired
    IdempotencyCleanupJob cleanupJob;

    @Test
    void keyReusableAfterRetention() throws Exception {
        String key = newKey();
        ObjectNode body = request("billing", "cust-1001");
        UUID original = idOf(submit(BILLING_KEY, key, body).andExpect(status().isAccepted()));

        // Within retention the key still replays.
        cleanupJob.clearExpiredKeys();
        submit(BILLING_KEY, key, body).andExpect(status().isOk());

        jdbc.sql("UPDATE notification SET created_at = created_at - interval '8 days' WHERE id = ?")
                .param(original).update();
        assertThat(cleanupJob.clearExpiredKeys()).isGreaterThanOrEqualTo(1);

        UUID fresh = idOf(submit(BILLING_KEY, key, body).andExpect(status().isAccepted()));
        assertThat(fresh).isNotEqualTo(original);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/notifications/" + original).header("X-API-Key", BILLING_KEY))
                .andExpect(status().isOk());
    }
}

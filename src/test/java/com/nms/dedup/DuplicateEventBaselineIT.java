package com.nms.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nms.api.ApiTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Documents the behavior being fixed: the same event with a new idempotency key is delivered twice. */
class DuplicateEventBaselineIT extends ApiTestSupport {

    @Test
    void sameEventWithNewKeyIsDeliveredTwice() throws Exception {
        ObjectNode body = request("billing", "cust-1001");
        submit(BILLING_KEY, newKey(), body).andExpect(status().isAccepted());
        submit(BILLING_KEY, newKey(), body).andExpect(status().isAccepted());

        List<String> statuses = jdbc.sql("""
                SELECT d.status FROM delivery d JOIN notification n ON n.id = d.notification_id
                WHERE n.source_system = 'billing' AND n.event_id = ? AND d.recipient_id = 'cust-1001'
                  AND d.channel = 'EMAIL'
                """).param(body.get("eventId").asText()).query(String.class).list();
        assertThat(statuses).containsExactly("PENDING", "PENDING");
    }
}

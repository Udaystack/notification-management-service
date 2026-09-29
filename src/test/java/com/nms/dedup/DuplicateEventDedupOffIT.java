package com.nms.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** With {@code nms.dedup.enabled=false} intake behaves exactly as before event deduplication. */
@TestPropertySource(properties = "nms.dedup.enabled=false")
class DuplicateEventDedupOffIT extends DedupTestSupport {

    @Test
    void sameEventWithNewKeyIsDeliveredTwice() throws Exception {
        ObjectNode body = request("billing", "cust-1001");
        submit(BILLING_KEY, newKey(), body).andExpect(status().isAccepted());
        submit(BILLING_KEY, newKey(), body).andExpect(status().isAccepted());

        assertThat(emailStatuses(body)).containsExactly("PENDING", "PENDING");
    }

    @Test
    void featureDisabled() throws Exception {
        ObjectNode body = request("billing", newEventId(), List.of("EMAIL"), "cust-1001");
        submit(BILLING_KEY, newKey(), body);
        UUID second = idOf(submit(BILLING_KEY, newKey(), body)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED")));

        Row delivery = onlyDelivery(second);
        assertThat(delivery.status()).isEqualTo("PENDING");
        assertThat(delivery.suppressedBy()).isNull();
        assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'DELIVERY_SUPPRESSED' "
                + "AND notification_id = ?", second)).isZero();
    }
}

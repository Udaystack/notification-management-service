package com.nms.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The behavior fixed by event deduplication: with the feature on, the same event with a new idempotency key is
 * delivered once and the resubmission is suppressed. {@link DuplicateEventDedupOffIT} pins the old behavior.
 */
@TestPropertySource(properties = "nms.dedup.enabled=true")
class DuplicateEventBaselineIT extends DedupTestSupport {

    @Test
    void sameEventWithNewKeyIsDeliveredOnce() throws Exception {
        ObjectNode body = request("billing", "cust-1001");
        submit(BILLING_KEY, newKey(), body).andExpect(status().isAccepted());
        submit(BILLING_KEY, newKey(), body).andExpect(status().isAccepted());

        assertThat(emailStatuses(body)).containsExactly("PENDING", "SUPPRESSED");
    }
}

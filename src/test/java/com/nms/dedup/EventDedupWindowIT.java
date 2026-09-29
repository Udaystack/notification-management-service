package com.nms.dedup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** A configured dedup window replaces the 24-hour default. */
@TestPropertySource(properties = {"nms.dedup.enabled=true", "nms.dedup.window=PT1H"})
class EventDedupWindowIT extends DedupTestSupport {

    @Test
    void windowConfigured() throws Exception {
        ObjectNode body = request("billing", newEventId(), List.of("EMAIL"), "cust-1001");
        UUID first = idOf(submit(BILLING_KEY, newKey(), body));
        backdate(first, Duration.ofHours(2));

        UUID second = idOf(submit(BILLING_KEY, newKey(), body));

        assertThat(onlyDelivery(second).status()).isEqualTo("PENDING");
    }
}

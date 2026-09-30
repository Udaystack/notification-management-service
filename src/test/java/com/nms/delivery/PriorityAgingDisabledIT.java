package com.nms.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "nms.worker.priority-aging=0")
class PriorityAgingDisabledIT extends PriorityTestSupport {

    @Test
    void agingDisabled() {
        UUID low = pending("LOW", Duration.ofHours(1));
        UUID high = pending("HIGH", Duration.ofMinutes(1));

        assertThat(worker.claim(10)).extracting(ClaimedDelivery::id).containsExactly(high, low);
    }
}

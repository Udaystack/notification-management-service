package com.nms.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Pins the claim order with strict priority (aging off) and the expiry metric, so changes to either show up as
 * reviewed diffs here.
 */
@TestPropertySource(properties = "nms.worker.priority-aging=0")
class ClaimOrderCharacterizationIT extends PriorityTestSupport {

    @Test
    void strictPriorityThenOldestDue() {
        UUID lowOld = pending("LOW", Duration.ofMinutes(60));
        UUID normalNew = pending("NORMAL", Duration.ofMinutes(2));
        UUID normalRetry = retry("NORMAL", Duration.ofMinutes(3));
        UUID normalReclaim = reclaimable("NORMAL", Duration.ofMinutes(10));
        UUID high = pending("HIGH", Duration.ofMinutes(1));

        assertThat(worker.claim(10)).extracting(ClaimedDelivery::id)
                .containsExactly(high, normalRetry, normalNew, normalReclaim, lowOld);
    }

    @Autowired
    MeterRegistry meters;

    private double expiredEmail() {
        return meters.find(DeliveryMetrics.EXPIRED).tag("channel", "EMAIL").counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    @Test
    void claimTimeAndRetryTimeExpiryAreCounted() throws Exception {
        double before = expiredEmail();
        UUID atClaim = pendingExpired("LOW");
        UUID atRetry = submitAndGetDelivery(request("billing", "cust-2003")
                .put("expiresAt", Instant.now().plusSeconds(10).toString()));

        worker.runOnce(10);

        assertThat(deliveryStatus(atClaim)).isEqualTo("EXPIRED");
        assertThat(deliveryStatus(atRetry)).isEqualTo("EXPIRED");
        assertThat(expiredEmail() - before).isEqualTo(2.0);
        assertThat(meters.find(DeliveryMetrics.EXPIRED).counters())
                .allSatisfy(c -> assertThat(c.getId().getTags()).extracting(t -> t.getKey()).containsExactly("channel"));
    }
}

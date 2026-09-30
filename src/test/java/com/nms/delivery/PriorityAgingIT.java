package com.nms.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Claim order with the default aging interval of 5 minutes. */
@TestPropertySource(properties = "nms.worker.priority-aging=5m")
class PriorityAgingIT extends PriorityTestSupport {

    @Test
    void waitingDeliveryGainsPriority() {
        UUID low = pending("LOW", Duration.ofMinutes(10));
        UUID high = pending("HIGH", Duration.ofMinutes(1));

        assertThat(worker.claim(10)).extracting(ClaimedDelivery::id).containsExactly(low, high);
        assertThat(storedPriority(low)).isEqualTo("LOW/0");
    }

    @Test
    void agingStepsOneLevelPerFullInterval() {
        UUID lowAtNormal = pending("LOW", Duration.ofMinutes(7));
        UUID normalFresh = pending("NORMAL", Duration.ofMinutes(4));
        UUID lowFresh = pending("LOW", Duration.ofMinutes(4));

        // LOW waiting 7m competes as NORMAL and is older than the fresh NORMAL; LOW waiting 4m is still LOW.
        assertThat(worker.claim(10)).extracting(ClaimedDelivery::id).containsExactly(lowAtNormal, normalFresh, lowFresh);
    }

    @Test
    void retryCompetesWithNewWork() {
        UUID newNormal = pending("NORMAL", Duration.ofMinutes(1));
        UUID highRetry = retry("HIGH", Duration.ofSeconds(30));
        UUID olderNormalRetry = retry("NORMAL", Duration.ofMinutes(2));

        assertThat(worker.claim(10)).extracting(ClaimedDelivery::id)
                .containsExactly(highRetry, olderNormalRetry, newNormal);
    }

    @Test
    void backoffDoesNotCountAsWaiting() {
        // The previous attempt failed long ago, but the retry only became due 1 minute ago: it is still LOW.
        UUID lowRetry = retry("LOW", Duration.ofMinutes(1));
        jdbc.sql("UPDATE delivery SET last_attempt_at = now() - interval '20 minutes' WHERE id = ?")
                .param(lowRetry).update();
        UUID normal = pending("NORMAL", Duration.ofSeconds(10));

        assertThat(worker.claim(10)).extracting(ClaimedDelivery::id).containsExactly(normal, lowRetry);
    }

    @Test
    void reclaimedLeaseOrderedByLeaseExpiry() {
        UUID newer = pending("NORMAL", Duration.ofMinutes(1));
        UUID reclaim = reclaimable("NORMAL", Duration.ofMinutes(2));

        assertThat(worker.claim(10)).extracting(ClaimedDelivery::id).containsExactly(reclaim, newer);
    }
}

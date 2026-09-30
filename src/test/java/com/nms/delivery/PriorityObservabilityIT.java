package com.nms.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class PriorityObservabilityIT extends PriorityTestSupport {

    @Autowired
    MeterRegistry meters;

    private double expired(String priority, boolean neverAttempted) {
        var counter = meters.find(DeliveryMetrics.EXPIRED).tags("channel", "EMAIL", "priority", priority,
                "neverAttempted", Boolean.toString(neverAttempted)).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void queueWaitMeasured() {
        Timer timer = meters.timer(DeliveryMetrics.QUEUE_WAIT, "priority", "NORMAL", "channel", "EMAIL");
        long countBefore = timer.count();
        double secondsBefore = timer.totalTime(TimeUnit.SECONDS);
        UUID delivery = pending("NORMAL", Duration.ofSeconds(90));

        assertThat(worker.claim(10)).extracting(ClaimedDelivery::id).containsExactly(delivery);

        assertThat(timer.count() - countBefore).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.SECONDS) - secondsBefore).isBetween(89.0, 100.0);

        // A later attempt of the same delivery (a retry that is due) records nothing more.
        jdbc.sql("UPDATE delivery SET status = 'RETRY_SCHEDULED', locked_until = NULL, "
                + "next_attempt_at = now() - interval '1 second' WHERE id = ?").param(delivery).update();
        assertThat(worker.claim(10)).extracting(ClaimedDelivery::id).containsExactly(delivery);
        assertThat(timer.count() - countBefore).isEqualTo(1);
    }

    @Test
    void neverAttemptedExpiryFlagged(CapturedOutput output) throws Exception {
        String subject = "Statement " + UUID.randomUUID();
        String body = "Your balance is $98,765";
        UUID delivery = submitAndGetDelivery(request("billing", "cust-1001").put("priority", "LOW")
                .put("subject", subject).put("body", body).put("expiresAt", Instant.now().plusSeconds(60).toString()));
        jdbc.sql("UPDATE notification SET expires_at = now() - interval '1 second' "
                + "WHERE id = (SELECT notification_id FROM delivery WHERE id = ?)").param(delivery).update();
        UUID notification = jdbc.sql("SELECT notification_id FROM delivery WHERE id = ?").param(delivery)
                .query(UUID.class).single();
        double before = expired("LOW", true);

        worker.runOnce(10);

        assertThat(deliveryStatus(delivery)).isEqualTo("EXPIRED");
        assertThat(expired("LOW", true) - before).isEqualTo(1.0);
        String warn = output.getAll().lines().filter(l -> l.contains("expired before any attempt"))
                .filter(l -> l.contains(delivery.toString())).findFirst().orElseThrow();
        assertThat(warn).contains("WARN", notification.toString(), "priority LOW");
        assertThat(output.getAll()).doesNotContain(subject, body, "jane.doe@example.com");
    }

    @Test
    void expiryAfterAnAttempt(CapturedOutput output) throws Exception {
        UUID delivery = submitAndGetDelivery(request("billing", "cust-2003").put("priority", "HIGH")
                .put("expiresAt", Instant.now().plusSeconds(10).toString()));
        double neverBefore = expired("HIGH", true);
        double attemptedBefore = expired("HIGH", false);

        worker.runOnce(10);

        assertThat(deliveryStatus(delivery)).isEqualTo("EXPIRED");
        assertThat(expired("HIGH", false) - attemptedBefore).isEqualTo(1.0);
        assertThat(expired("HIGH", true) - neverBefore).isZero();
        assertThat(output.getAll().lines().filter(l -> l.contains(delivery.toString()))
                .noneMatch(l -> l.contains("expired before any attempt"))).isTrue();
    }
}

package com.nms.retry;

import static org.assertj.core.api.Assertions.assertThat;

import com.nms.common.domain.FailureClass;
import com.nms.retry.RetryPolicy.RetryDecision;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    private static RetryPolicy policy(double random) {
        return new RetryPolicy(Duration.ofSeconds(2), Duration.ofMinutes(5), 5, () -> random);
    }

    @Test
    void backoffGrowsExponentiallyWithFullJitter() {
        RetryPolicy maxJitter = policy(0.999999);
        assertThat(maxJitter.backoff(1)).isBetween(Duration.ofMillis(1999), Duration.ofSeconds(2));
        assertThat(maxJitter.backoff(2)).isBetween(Duration.ofMillis(3999), Duration.ofSeconds(4));
        assertThat(maxJitter.backoff(3)).isBetween(Duration.ofMillis(7999), Duration.ofSeconds(8));

        assertThat(policy(0.5).backoff(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy(0.0).backoff(3)).isEqualTo(Duration.ZERO);
    }

    @Test
    void backoffIsCappedAtMaxDelay() {
        assertThat(policy(0.999999).backoff(20)).isBetween(Duration.ofMillis(299_999), Duration.ofMinutes(5));
        assertThat(policy(0.5).backoff(30)).isEqualTo(Duration.ofSeconds(150));
    }

    @ParameterizedTest
    @EnumSource(value = FailureClass.class, names = {"TRANSIENT", "TIMEOUT", "RATE_LIMITED"})
    void retryableFailureIsRetried(FailureClass failureClass) {
        RetryDecision decision = policy(0.5).decide(failureClass, 1, null, NOW, null);
        assertThat(decision).isEqualTo(new RetryDecision.Retry(NOW.plusSeconds(1)));
    }

    @ParameterizedTest
    @EnumSource(value = FailureClass.class, names = {"PERMANENT_REJECTION", "INVALID_RECIPIENT", "AUTH_ERROR"})
    void nonRetryableFailureFailsImmediately(FailureClass failureClass) {
        assertThat(policy(0.5).decide(failureClass, 1, null, NOW, null))
                .isEqualTo(new RetryDecision.Fail(failureClass, false));
    }

    @Test
    void failsAsExhaustedAfterMaxAttempts() {
        assertThat(policy(0.5).decide(FailureClass.TIMEOUT, 4, null, NOW, null))
                .isInstanceOf(RetryDecision.Retry.class);
        assertThat(policy(0.5).decide(FailureClass.TIMEOUT, 5, null, NOW, null))
                .isEqualTo(new RetryDecision.Fail(FailureClass.TIMEOUT, true));
    }

    @Test
    void retryAfterIsAFloorOnTheDelay() {
        assertThat(policy(0.5).decide(FailureClass.RATE_LIMITED, 1, Duration.ofSeconds(30), NOW, null))
                .isEqualTo(new RetryDecision.Retry(NOW.plusSeconds(30)));
        // A backoff longer than retry-after wins.
        assertThat(policy(0.999999).decide(FailureClass.RATE_LIMITED, 4, Duration.ofSeconds(1), NOW, null))
                .isInstanceOf(RetryDecision.Retry.class)
                .matches(d -> ((RetryDecision.Retry) d).nextAttemptAt().isAfter(NOW.plusSeconds(15)));
    }

    @Test
    void expiresWhenNextAttemptWouldBeAfterExpiry() {
        assertThat(policy(0.5).decide(FailureClass.TRANSIENT, 1, Duration.ofSeconds(30), NOW, NOW.plusSeconds(10)))
                .isEqualTo(new RetryDecision.Expire());
        assertThat(policy(0.5).decide(FailureClass.TRANSIENT, 1, Duration.ofSeconds(30), NOW, NOW.plusSeconds(30)))
                .isEqualTo(new RetryDecision.Retry(NOW.plusSeconds(30)));
    }
}

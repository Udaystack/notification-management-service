package com.nms.retry;

import com.nms.common.domain.FailureClass;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * Decides what happens after a failed attempt: exponential backoff with full jitter, a retry-after
 * floor for rate limiting, a maximum number of attempts, and a cutoff at the notification's expiry.
 */
public final class RetryPolicy {

    private final Duration baseDelay;
    private final Duration maxDelay;
    private final int maxAttempts;
    private final DoubleSupplier random;

    /**
     * @param random source of uniformly distributed values in [0, 1); injected so tests are deterministic
     */
    public RetryPolicy(Duration baseDelay, Duration maxDelay, int maxAttempts, DoubleSupplier random) {
        this.baseDelay = Objects.requireNonNull(baseDelay);
        this.maxDelay = Objects.requireNonNull(maxDelay);
        this.maxAttempts = maxAttempts;
        this.random = Objects.requireNonNull(random);
    }

    /**
     * @param failureClass classification of the failed attempt
     * @param attemptsMade attempts made so far, including the one that just failed
     * @param retryAfter provider-supplied minimum wait, or {@code null}
     * @param now time of the failed attempt
     * @param expiresAt notification expiry, or {@code null} if it never expires
     */
    public RetryDecision decide(
            FailureClass failureClass, int attemptsMade, Duration retryAfter, Instant now, Instant expiresAt) {
        if (!failureClass.isRetryable()) {
            return new RetryDecision.Fail(failureClass, false);
        }
        if (attemptsMade >= maxAttempts) {
            return new RetryDecision.Fail(failureClass, true);
        }
        Duration delay = backoff(attemptsMade);
        if (retryAfter != null && retryAfter.compareTo(delay) > 0) {
            delay = retryAfter;
        }
        Instant next = now.plus(delay);
        if (expiresAt != null && next.isAfter(expiresAt)) {
            return new RetryDecision.Expire();
        }
        return new RetryDecision.Retry(next);
    }

    /** Full jitter: uniform in [0, min(maxDelay, base * 2^(attempt-1))). */
    Duration backoff(int attemptsMade) {
        long baseMillis = baseDelay.toMillis();
        int shift = Math.min(attemptsMade - 1, 30);
        long exponential = baseMillis > (Long.MAX_VALUE >> shift) ? Long.MAX_VALUE : baseMillis << shift;
        long cap = Math.min(maxDelay.toMillis(), exponential);
        return Duration.ofMillis((long) (random.getAsDouble() * cap));
    }

    public sealed interface RetryDecision {

        record Retry(Instant nextAttemptAt) implements RetryDecision {}

        /** @param exhausted {@code true} when a retryable failure ran out of attempts */
        record Fail(FailureClass failureClass, boolean exhausted) implements RetryDecision {}

        record Expire() implements RetryDecision {}
    }
}

package com.nms.channel;

import com.nms.common.domain.FailureClass;
import java.time.Duration;

/** Outcome of a provider call. Providers classify their own errors; the worker never inspects them. */
public sealed interface DeliveryResult {

    record Success() implements DeliveryResult {}

    /**
     * @param retryAfter provider-supplied minimum wait before retrying, or {@code null}
     * @param reasonCode audit reason when the delivery fails for good, or {@code null} to use the failure class
     */
    record Failure(FailureClass failureClass, Duration retryAfter, String reasonCode) implements DeliveryResult {

        public Failure(FailureClass failureClass) {
            this(failureClass, null, null);
        }

        public Failure(FailureClass failureClass, Duration retryAfter) {
            this(failureClass, retryAfter, null);
        }
    }
}

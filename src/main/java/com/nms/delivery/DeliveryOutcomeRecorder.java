package com.nms.delivery;

import com.nms.audit.AuditDetails;
import com.nms.audit.AuditService;
import com.nms.channel.DeliveryResult;
import com.nms.common.domain.AuditEventType;
import com.nms.common.domain.DeliveryStatus;
import com.nms.common.domain.FailureClass;
import com.nms.retry.RetryPolicy;
import com.nms.retry.RetryPolicy.RetryDecision;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the outcome of an attempt in its own transaction. The update is conditional on the claim's version, so a
 * worker whose lease expired and whose delivery was reclaimed cannot overwrite the newer state; its result is
 * discarded.
 */
@Component
class DeliveryOutcomeRecorder {

    static final String RETRIES_EXHAUSTED = "RETRIES_EXHAUSTED";

    private static final Logger log = LoggerFactory.getLogger(DeliveryOutcomeRecorder.class);

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final NotificationStatusUpdater statusUpdater;
    private final RetryPolicy retryPolicy;
    private final DeliveryMetrics metrics;
    private final Clock clock;

    DeliveryOutcomeRecorder(JdbcClient jdbc, AuditService audit, NotificationStatusUpdater statusUpdater,
            RetryPolicy retryPolicy, DeliveryMetrics metrics, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.statusUpdater = statusUpdater;
        this.retryPolicy = retryPolicy;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * @param rawAddress the full address used for the attempt (masked before it reaches audit), or {@code null}
     */
    @Transactional
    public void record(ClaimedDelivery claim, DeliveryResult result, String rawAddress) {
        Instant now = clock.instant();
        AuditDetails details = AuditDetails.create()
                .recipientId(claim.recipientId())
                .channel(claim.channel())
                .attempt(claim.attempt());
        if (rawAddress != null) {
            details.address(claim.channel(), rawAddress);
        }

        if (result instanceof DeliveryResult.Success) {
            if (update(claim, DeliveryStatus.SENT, null, null, now)) {
                audit.record(claim.notificationId(), claim.id(), claim.sourceSystem(),
                        AuditEventType.DELIVERY_SUCCEEDED, null, details.build());
                metrics.sent(claim.channel());
                statusUpdater.recompute(claim.notificationId(), now);
            }
            return;
        }

        DeliveryResult.Failure failure = (DeliveryResult.Failure) result;
        FailureClass failureClass = failure.failureClass();
        details.failureClass(failureClass);
        if (failure.retryAfter() != null) {
            details.retryAfter(failure.retryAfter());
        }
        RetryDecision decision = retryPolicy.decide(
                failureClass, claim.attempt(), failure.retryAfter(), now, claim.expiresAt());

        switch (decision) {
            case RetryDecision.Retry retry -> {
                if (update(claim, DeliveryStatus.RETRY_SCHEDULED, failureClass, retry.nextAttemptAt(), now)) {
                    audit.record(claim.notificationId(), claim.id(), claim.sourceSystem(),
                            AuditEventType.RETRY_SCHEDULED, failureClass.name(),
                            details.nextAttemptAt(retry.nextAttemptAt()).build());
                    metrics.retried(claim.channel(), failureClass);
                }
            }
            case RetryDecision.Fail fail -> {
                if (update(claim, DeliveryStatus.FAILED, failureClass, null, now)) {
                    String reason = fail.exhausted() ? RETRIES_EXHAUSTED
                            : failure.reasonCode() != null ? failure.reasonCode() : failureClass.name();
                    audit.record(claim.notificationId(), claim.id(), claim.sourceSystem(),
                            AuditEventType.DELIVERY_FAILED, reason, details.build());
                    metrics.failed(claim.channel(), failureClass);
                    if (failureClass == FailureClass.AUTH_ERROR) {
                        log.error("Provider rejected NMS credentials for channel {} (delivery {}); "
                                + "all {} deliveries will fail until the provider configuration is fixed",
                                claim.channel(), claim.id(), claim.channel());
                    }
                }
            }
            case RetryDecision.Expire expire -> {
                if (update(claim, DeliveryStatus.EXPIRED, failureClass, null, now)) {
                    audit.record(claim.notificationId(), claim.id(), claim.sourceSystem(),
                            AuditEventType.DELIVERY_EXPIRED, null, details.build());
                    metrics.expired(claim.channel());
                }
            }
        }
        statusUpdater.recompute(claim.notificationId(), now);
    }

    private boolean update(ClaimedDelivery claim, DeliveryStatus to, FailureClass failureClass,
            java.time.Instant nextAttemptAt, Instant now) {
        DeliveryStateMachine.transition(DeliveryStatus.IN_FLIGHT, to);
        int updated = jdbc.sql("""
                UPDATE delivery SET status = :status, locked_until = NULL,
                    next_attempt_at = :nextAttemptAt,
                    last_failure_class = COALESCE(:failureClass, last_failure_class),
                    completed_at = CASE WHEN :terminal THEN :now ELSE completed_at END,
                    version = version + 1
                WHERE id = :id AND status = 'IN_FLIGHT' AND version = :version
                """)
                .param("status", to.name())
                .param("nextAttemptAt", nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt))
                .param("failureClass", failureClass == null ? null : failureClass.name())
                .param("terminal", to.isTerminal())
                .param("now", Timestamp.from(now))
                .param("id", claim.id())
                .param("version", claim.version())
                .update();
        if (updated == 0) {
            log.warn("Discarding stale outcome {} for delivery {}: it was reclaimed after the lease expired",
                    to, claim.id());
            return false;
        }
        return true;
    }
}

package com.nms.common.config;

import com.nms.common.domain.Channel;
import com.nms.common.domain.NotificationType;
import com.nms.common.domain.Severity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** All tunable settings of the service, bound from the {@code nms.*} namespace. */
@Validated
@ConfigurationProperties(prefix = "nms")
public record NmsProperties(
        @Valid @NotNull Intake intake,
        @Valid @NotNull Routing routing,
        @Valid @NotNull Idempotency idempotency,
        @Valid @NotNull Retry retry,
        @Valid @NotNull Worker worker,
        @Valid @NotNull Dedup dedup,
        @Valid @NotNull Webhook webhook) {

    public record Intake(@Min(1) int maxRecipients) {}

    public record Routing(
            @NotNull Map<NotificationType, List<Channel>> defaultChannels,
            @NotNull Map<Severity, List<Channel>> severityEscalation,
            @NotNull Channel fallbackChannel) {}

    public record Idempotency(@NotNull Duration retention, @NotNull Duration cleanupInterval) {}

    public record Retry(@NotNull Duration baseDelay, @NotNull Duration maxDelay, @Min(1) int maxAttempts) {}

    public record Worker(
            boolean enabled,
            @Min(1) int concurrency,
            @Min(1) int batchSize,
            @NotNull Duration pollInterval,
            @NotNull Duration leaseDuration,
            @NotNull Duration providerTimeout,
            @NotNull Duration shutdownTimeout) {}

    /**
     * Event-level deduplication.
     *
     * @param window how far back an earlier delivery of the same event counts as a duplicate; must be positive
     */
    public record Dedup(boolean enabled, @NotNull Duration window) {

        public Dedup {
            if (window != null && (window.isZero() || window.isNegative())) {
                throw new IllegalArgumentException("nms.dedup.window must be a positive duration, but was " + window);
            }
        }
    }

    /**
     * The {@code WEBHOOK} channel.
     *
     * @param signingSecret HMAC key for request signatures; required when enabled, never printed
     * @param allowPrivateHosts also allow {@code http} and non-public addresses (local development and tests only)
     */
    public record Webhook(boolean enabled, String signingSecret, boolean allowPrivateHosts) {

        public Webhook {
            if (enabled && (signingSecret == null || signingSecret.isBlank())) {
                throw new IllegalArgumentException(
                        "nms.webhook.signing-secret must be set when nms.webhook.enabled is true");
            }
        }

        @Override
        public String toString() {
            return "Webhook[enabled=" + enabled + ", signingSecret=***, allowPrivateHosts=" + allowPrivateHosts + "]";
        }
    }
}

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
        @Valid @NotNull Worker worker) {

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
}

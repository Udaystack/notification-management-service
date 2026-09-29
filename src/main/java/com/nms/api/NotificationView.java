package com.nms.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Status resource. Never contains subject, body, or unmasked addresses. */
public record NotificationView(
        UUID id,
        String eventId,
        String type,
        String severity,
        String priority,
        String status,
        List<String> selectedChannels,
        Instant createdAt,
        Instant scheduledAt,
        Instant expiresAt,
        List<DeliveryView> deliveries) {

    public record DeliveryView(
            UUID id,
            String recipientId,
            String channel,
            String address,
            String status,
            int attemptCount,
            String lastFailureClass,
            SuppressedBy suppressedBy,
            Instant lastAttemptAt,
            Instant nextAttemptAt,
            Instant completedAt) {}

    /** The original delivery a {@code SUPPRESSED} delivery duplicates; always of the same source system. */
    public record SuppressedBy(UUID notificationId, UUID deliveryId) {}
}

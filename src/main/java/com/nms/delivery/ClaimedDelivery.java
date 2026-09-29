package com.nms.delivery;

import com.nms.common.domain.Channel;
import java.time.Instant;
import java.util.UUID;

/**
 * A delivery a worker holds under lease.
 *
 * @param attempt the attempt number this claim counts as (1-based)
 * @param version row version after the claim; the outcome update is conditional on it
 */
public record ClaimedDelivery(
        UUID id,
        UUID notificationId,
        String sourceSystem,
        String recipientId,
        Channel channel,
        int attempt,
        long version,
        String subject,
        String body,
        Instant expiresAt) {

    @Override
    public String toString() {
        // Never expose message content in logs.
        return "ClaimedDelivery[" + id + ", notification " + notificationId + ", " + channel + ", attempt " + attempt + "]";
    }
}

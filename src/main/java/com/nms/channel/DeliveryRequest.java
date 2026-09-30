package com.nms.channel;

import com.nms.common.domain.Channel;
import java.util.UUID;

/**
 * What a provider needs to send one message.
 *
 * @param idempotencyKey the delivery ID; a provider must not send twice for the same key
 * @param attempt 1-based attempt number
 * @param notification the notification this delivery belongs to, or {@code null} when a provider doesn't need it
 */
public record DeliveryRequest(
        UUID idempotencyKey, Channel channel, String address, String subject, String body, int attempt,
        NotificationInfo notification) {

    public DeliveryRequest(UUID idempotencyKey, Channel channel, String address, String subject, String body,
            int attempt) {
        this(idempotencyKey, channel, address, subject, body, attempt, null);
    }

    /** Notification fields a provider may pass on (the webhook payload); never contains content or addresses. */
    public record NotificationInfo(UUID notificationId, String eventId, String sourceSystem, String type,
            String severity, String priority, String recipientId) {}

    @Override
    public String toString() {
        // Never expose address or content in logs.
        return "DeliveryRequest[" + idempotencyKey + ", " + channel + ", attempt " + attempt + "]";
    }
}

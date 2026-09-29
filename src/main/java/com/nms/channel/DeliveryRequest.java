package com.nms.channel;

import com.nms.common.domain.Channel;
import java.util.UUID;

/**
 * What a provider needs to send one message.
 *
 * @param idempotencyKey the delivery ID; a provider must not send twice for the same key
 * @param attempt 1-based attempt number
 */
public record DeliveryRequest(
        UUID idempotencyKey, Channel channel, String address, String subject, String body, int attempt) {

    @Override
    public String toString() {
        // Never expose address or content in logs.
        return "DeliveryRequest[" + idempotencyKey + ", " + channel + ", attempt " + attempt + "]";
    }
}

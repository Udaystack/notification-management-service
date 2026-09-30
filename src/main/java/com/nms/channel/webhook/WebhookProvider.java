package com.nms.channel.webhook;

import com.nms.channel.ChannelProvider;
import com.nms.channel.DeliveryRequest;
import com.nms.channel.DeliveryResult;
import com.nms.common.config.NmsProperties;
import com.nms.common.domain.Channel;
import com.nms.common.domain.FailureClass;
import org.springframework.stereotype.Component;

/**
 * Sends {@code WEBHOOK} deliveries. Always registered, so queued webhook deliveries are settled even while the
 * channel is disabled: then no request is sent and the delivery fails with reason {@code CHANNEL_DISABLED}.
 */
@Component
public class WebhookProvider implements ChannelProvider {

    public static final String CHANNEL_DISABLED = "CHANNEL_DISABLED";

    private final NmsProperties.Webhook config;

    WebhookProvider(NmsProperties properties) {
        this.config = properties.webhook();
    }

    @Override
    public Channel channel() {
        return Channel.WEBHOOK;
    }

    @Override
    public DeliveryResult send(DeliveryRequest request) {
        if (!config.enabled()) {
            return new DeliveryResult.Failure(FailureClass.PERMANENT_REJECTION, null, CHANNEL_DISABLED);
        }
        throw new UnsupportedOperationException("webhook sending is not implemented yet");
    }
}

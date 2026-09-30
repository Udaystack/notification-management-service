package com.nms.routing;

import com.nms.common.config.NmsProperties;
import com.nms.common.domain.Channel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RoutingConfig {

    @Bean
    RoutingPolicy routingPolicy(NmsProperties properties) {
        NmsProperties.Routing routing = properties.routing();
        rejectWebhook("nms.routing.default-channels", routing.defaultChannels());
        rejectWebhook("nms.routing.severity-escalation", routing.severityEscalation());
        if (routing.fallbackChannel() == Channel.WEBHOOK) {
            throw webhookNotAllowed("nms.routing.fallback-channel");
        }
        Set<Channel> disabled = properties.webhook().enabled() ? Set.of() : Set.of(Channel.WEBHOOK);
        return new RoutingPolicy(routing.defaultChannels(), routing.severityEscalation(), routing.fallbackChannel(),
                disabled);
    }

    /** WEBHOOK is selected only when a request asks for it (see the channel-routing spec). */
    private static void rejectWebhook(String property, Map<?, List<Channel>> channels) {
        if (channels.values().stream().anyMatch(list -> list.contains(Channel.WEBHOOK))) {
            throw webhookNotAllowed(property);
        }
    }

    private static IllegalStateException webhookNotAllowed(String property) {
        return new IllegalStateException(property + " must not contain WEBHOOK: the webhook channel is only used "
                + "when a request lists it in channels");
    }
}

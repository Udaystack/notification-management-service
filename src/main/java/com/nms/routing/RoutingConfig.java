package com.nms.routing;

import com.nms.common.config.NmsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RoutingConfig {

    @Bean
    RoutingPolicy routingPolicy(NmsProperties properties) {
        NmsProperties.Routing routing = properties.routing();
        return new RoutingPolicy(routing.defaultChannels(), routing.severityEscalation(), routing.fallbackChannel());
    }
}

package com.nms.channel;

import com.nms.common.domain.Channel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Simulated EMAIL, SMS, and PUSH providers; swap for real adapters behind {@link ChannelProvider}. */
@Configuration(proxyBeanMethods = false)
class SimulatedProvidersConfig {

    @Bean
    SimulatedProvider emailProvider() {
        return new SimulatedProvider(Channel.EMAIL);
    }

    @Bean
    SimulatedProvider smsProvider() {
        return new SimulatedProvider(Channel.SMS);
    }

    @Bean
    SimulatedProvider pushProvider() {
        return new SimulatedProvider(Channel.PUSH);
    }
}

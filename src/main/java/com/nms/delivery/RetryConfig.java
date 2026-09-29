package com.nms.delivery;

import com.nms.common.config.NmsProperties;
import com.nms.retry.RetryPolicy;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RetryConfig {

    @Bean
    RetryPolicy retryPolicy(NmsProperties properties) {
        NmsProperties.Retry retry = properties.retry();
        return new RetryPolicy(retry.baseDelay(), retry.maxDelay(), retry.maxAttempts(),
                () -> ThreadLocalRandom.current().nextDouble());
    }
}

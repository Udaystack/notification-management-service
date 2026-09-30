package com.nms.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.nms.common.config.NmsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class RoutingConfigGuardTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NmsProperties.class)
    @Import(RoutingConfig.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(Config.class)
            .withPropertyValues(
                    "nms.intake.max-recipients=100",
                    "nms.idempotency.retention=7d", "nms.idempotency.cleanup-interval=1h",
                    "nms.retry.base-delay=2s", "nms.retry.max-delay=5m", "nms.retry.max-attempts=5",
                    "nms.worker.enabled=false", "nms.worker.concurrency=1", "nms.worker.batch-size=1",
                    "nms.worker.poll-interval=1s", "nms.worker.lease-duration=60s",
                    "nms.worker.provider-timeout=10s", "nms.worker.shutdown-timeout=1s", "nms.worker.priority-aging=5m",
                    "nms.dedup.enabled=true", "nms.dedup.window=24h", "nms.webhook.enabled=false");

    private static final String[] VALID = {
        "nms.routing.default-channels.TRANSACTIONAL=EMAIL",
        "nms.routing.severity-escalation.CRITICAL=SMS",
        "nms.routing.fallback-channel=EMAIL"};

    @Test
    void validConfigurationStarts() {
        runner.withPropertyValues(VALID).run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(RoutingPolicy.class));
    }

    @Test
    void webhookInRoutingConfigurationRejected() {
        assertRejected("nms.routing.default-channels", "nms.routing.default-channels.TRANSACTIONAL=EMAIL,WEBHOOK");
        assertRejected("nms.routing.severity-escalation", "nms.routing.severity-escalation.CRITICAL=SMS,WEBHOOK");
        assertRejected("nms.routing.fallback-channel", "nms.routing.fallback-channel=WEBHOOK");
    }

    private void assertRejected(String property, String override) {
        runner.withPropertyValues(VALID).withPropertyValues(override).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause()
                    .hasMessageContaining(property + " must not contain WEBHOOK");
        });
    }
}

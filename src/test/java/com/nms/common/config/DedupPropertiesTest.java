package com.nms.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class DedupPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NmsProperties.class)
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(Props.class)
            .withPropertyValues(
                    "nms.intake.max-recipients=100",
                    "nms.routing.default-channels.TRANSACTIONAL=EMAIL",
                    "nms.routing.severity-escalation.CRITICAL=SMS",
                    "nms.routing.fallback-channel=EMAIL",
                    "nms.idempotency.retention=7d", "nms.idempotency.cleanup-interval=1h",
                    "nms.retry.base-delay=2s", "nms.retry.max-delay=5m", "nms.retry.max-attempts=5",
                    "nms.worker.enabled=false", "nms.worker.concurrency=1", "nms.worker.batch-size=1",
                    "nms.worker.poll-interval=1s", "nms.worker.lease-duration=60s",
                    "nms.worker.provider-timeout=10s", "nms.worker.shutdown-timeout=1s",
                    "nms.dedup.enabled=true");

    @Test
    void positiveWindowIsAccepted() {
        runner.withPropertyValues("nms.dedup.window=24h").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(NmsProperties.class).dedup().window()).isEqualTo(Duration.ofHours(24));
        });
    }

    @Test
    void zeroOrNegativeWindowFailsStartupWithClearMessage() {
        for (String window : new String[] {"0s", "-1h"}) {
            runner.withPropertyValues("nms.dedup.window=" + window).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).rootCause()
                        .hasMessageContaining("nms.dedup.window must be a positive duration");
            });
        }
    }
}

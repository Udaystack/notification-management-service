package com.nms.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PriorityAgingPropertiesTest {

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
                    "nms.dedup.enabled=true", "nms.dedup.window=24h", "nms.webhook.enabled=false");

    @Test
    void negativeAgingIntervalRejected() {
        runner.withPropertyValues("nms.worker.priority-aging=-1m").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause()
                    .hasMessageContaining("nms.worker.priority-aging must not be negative");
        });
    }

    @Test
    void zeroAndPositiveIntervalsStart() {
        for (String interval : new String[] {"0", "5m"}) {
            runner.withPropertyValues("nms.worker.priority-aging=" + interval).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBean(NmsProperties.class).worker().priorityAging())
                        .isEqualTo(interval.equals("0") ? Duration.ZERO : Duration.ofMinutes(5));
            });
        }
    }
}

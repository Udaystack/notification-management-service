package com.nms.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class WebhookPropertiesTest {

    private static final String SECRET = "test-signing-secret-5f1c";

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
                    "nms.worker.provider-timeout=10s", "nms.worker.shutdown-timeout=1s", "nms.worker.priority-aging=5m",
                    "nms.dedup.enabled=true", "nms.dedup.window=24h");

    @Test
    void signingSecretRequiredWhenEnabled() {
        for (String secret : new String[] {"", "   "}) {
            runner.withPropertyValues("nms.webhook.enabled=true", "nms.webhook.signing-secret=" + secret).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).rootCause()
                        .hasMessageContaining("nms.webhook.signing-secret must be set");
            });
        }
        runner.withPropertyValues("nms.webhook.enabled=true").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void secretOnlyRequiredWhenEnabled() {
        runner.withPropertyValues("nms.webhook.enabled=false").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(NmsProperties.class).webhook().allowPrivateHosts()).isFalse();
        });
        runner.withPropertyValues("nms.webhook.enabled=true", "nms.webhook.signing-secret=" + SECRET).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(NmsProperties.class).webhook().signingSecret()).isEqualTo(SECRET);
        });
    }

    @Test
    void toStringNeverContainsTheSecret() {
        runner.withPropertyValues("nms.webhook.enabled=true", "nms.webhook.signing-secret=" + SECRET).run(ctx -> {
            NmsProperties properties = ctx.getBean(NmsProperties.class);
            assertThat(properties.webhook().toString()).doesNotContain(SECRET).contains("signingSecret=***");
            assertThat(properties.toString()).doesNotContain(SECRET);
        });
    }
}

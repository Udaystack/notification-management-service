package com.nms.channel.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.nms.channel.DeliveryResult;
import com.nms.common.domain.FailureClass;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WebhookProviderTest {

    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");

    private static FailureClass failureClass(int status) {
        return ((DeliveryResult.Failure) WebhookProvider.classify(status, null, NOW)).failureClass();
    }

    @Test
    void statusCodesMapToFailureClasses() {
        assertThat(WebhookProvider.classify(200, null, NOW)).isInstanceOf(DeliveryResult.Success.class);
        assertThat(WebhookProvider.classify(204, null, NOW)).isInstanceOf(DeliveryResult.Success.class);
        assertThat(failureClass(500)).isEqualTo(FailureClass.TRANSIENT);
        assertThat(failureClass(503)).isEqualTo(FailureClass.TRANSIENT);
        assertThat(failureClass(429)).isEqualTo(FailureClass.RATE_LIMITED);
        assertThat(failureClass(401)).isEqualTo(FailureClass.AUTH_ERROR);
        assertThat(failureClass(403)).isEqualTo(FailureClass.AUTH_ERROR);
        assertThat(failureClass(404)).isEqualTo(FailureClass.INVALID_RECIPIENT);
        assertThat(failureClass(410)).isEqualTo(FailureClass.INVALID_RECIPIENT);
        for (int status : new int[] {300, 301, 302, 307, 400, 405, 408, 413, 422}) {
            assertThat(failureClass(status)).as("status %d", status).isEqualTo(FailureClass.PERMANENT_REJECTION);
        }
    }

    @Test
    void retryAfterParsing() {
        assertThat(WebhookProvider.retryAfter("30", NOW)).isEqualTo(Duration.ofSeconds(30));
        assertThat(WebhookProvider.retryAfter(" 0 ", NOW)).isEqualTo(Duration.ZERO);
        assertThat(WebhookProvider.retryAfter("Tue, 29 Sep 2026 10:02:00 GMT", NOW)).isEqualTo(Duration.ofMinutes(2));
        assertThat(WebhookProvider.retryAfter("Tue, 29 Sep 2026 09:00:00 GMT", NOW)).isEqualTo(Duration.ZERO);
        assertThat(WebhookProvider.retryAfter("soon", NOW)).isNull();
        assertThat(WebhookProvider.retryAfter("-5", NOW)).isNull();
        assertThat(WebhookProvider.retryAfter(null, NOW)).isNull();
        assertThat(((DeliveryResult.Failure) WebhookProvider.classify(429, "30", NOW)).retryAfter())
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void signatureIsHmacSha256OverTimestampAndBody() {
        // Reference value: printf '1790676000.{"a":1}' | openssl dgst -sha256 -hmac secret
        assertThat(WebhookProvider.sign("secret", "1790676000", "{\"a\":1}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("sha256=211080cfb0a89486728e1bfb8c18743f52d388caf0e07e35fdb80e0b7c590ba6");
    }
}

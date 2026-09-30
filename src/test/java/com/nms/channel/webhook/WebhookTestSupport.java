package com.nms.channel.webhook;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.nms.delivery.DeliveryTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Webhook tests: a WireMock endpoint on localhost and recipients whose webhook URL points at it. */
abstract class WebhookTestSupport extends DeliveryTestSupport {

    static final String SECRET = "test-webhook-secret-9a41";

    @RegisterExtension
    static WireMockExtension endpoint = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    /** A new recipient with a {@code WEBHOOK} address (and an {@code EMAIL} address for fallback). */
    protected String webhookRecipient(String url) {
        String id = "wh-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("INSERT INTO recipient (id, display_name) VALUES (?, 'Webhook test')").param(id).update();
        jdbc.sql("INSERT INTO recipient_channel (recipient_id, channel, address, opted_out) VALUES (?, 'WEBHOOK', ?, FALSE)")
                .param(id).param(url).update();
        jdbc.sql("INSERT INTO recipient_channel (recipient_id, channel, address, opted_out) "
                + "VALUES (?, 'EMAIL', 'webhook-test@example.com', FALSE)").param(id).update();
        return id;
    }

    protected String endpointUrl(String path) {
        return endpoint.baseUrl() + path;
    }

    /** Queues a due {@code WEBHOOK} delivery directly, bypassing routing; returns the delivery ID. */
    protected UUID queueWebhookDelivery(String recipientId) {
        UUID notificationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO notification (id, source_system, event_id, type, severity, priority, priority_rank,
                    subject, body, body_hash, status, created_at, updated_at)
                VALUES (?, 'billing', ?, 'ALERT', 'MEDIUM', 'NORMAL', 1, 'Subject', 'Body', repeat('0', 64),
                    'ACCEPTED', now(), now())
                """).param(notificationId).param("EVT-" + UUID.randomUUID()).update();
        UUID deliveryId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO delivery (id, notification_id, recipient_id, channel, address_masked, status,
                    next_attempt_at, created_at)
                VALUES (?, ?, ?, 'WEBHOOK', '***', 'PENDING', now() - interval '1 second', now())
                """).param(deliveryId).param(notificationId).param(recipientId).update();
        return deliveryId;
    }

    protected String lastFailureClass(UUID deliveryId) {
        return jdbc.sql("SELECT last_failure_class FROM delivery WHERE id = ?").param(deliveryId)
                .query(String.class).single();
    }

    protected String failedReason(UUID deliveryId) {
        return jdbc.sql("SELECT reason_code FROM audit_event WHERE delivery_id = ? AND event_type = 'DELIVERY_FAILED'")
                .param(deliveryId).query(String.class).single();
    }
}

package com.nms.channel.webhook;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** With {@code nms.webhook.enabled=false} no webhook request is ever sent. */
@TestPropertySource(properties = "nms.webhook.enabled=false")
class WebhookDisabledIT extends WebhookTestSupport {

    @Test
    void queuedWebhookDeliveryFailsWhileDisabled() {
        endpoint.stubFor(post(anyUrl()).willReturn(ok()));
        UUID delivery = queueWebhookDelivery(webhookRecipient(endpointUrl("/hooks/disabled")));

        worker.runOnce(10);

        assertThat(deliveryStatus(delivery)).isEqualTo("FAILED");
        assertThat(lastFailureClass(delivery)).isEqualTo("PERMANENT_REJECTION");
        assertThat(attempts(delivery)).isEqualTo(1);
        assertThat(failedReason(delivery)).isEqualTo("CHANNEL_DISABLED");
        assertThat(auditTypes(delivery)).containsExactly("DELIVERY_ATTEMPTED", "DELIVERY_FAILED");
        endpoint.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void disabledWebhookFallsBack() throws Exception {
        ObjectNode body = request("billing", "cust-3001");
        body.putArray("channels").add("WEBHOOK");
        UUID id = idOf(submit(BILLING_KEY, newKey(), body).andExpect(status().isAccepted()));

        assertThat(jdbc.sql("SELECT channel FROM delivery WHERE notification_id = ?").param(id)
                .query(String.class).list()).isEqualTo(List.of("EMAIL"));
        JsonNode routing = mapper.readTree(jdbc.sql("""
                SELECT details::text FROM audit_event WHERE notification_id = ? AND event_type = 'ROUTING_DECIDED'
                """).param(id).query(String.class).single());
        assertThat(routing.get("removed").get("WEBHOOK").asText()).isEqualTo("CHANNEL_DISABLED");
        assertThat(routing.get("added").get("EMAIL").asText()).isEqualTo("FALLBACK");
        assertThat(routing.get("selectedChannels")).extracting(JsonNode::asText).containsExactly("EMAIL");
    }
}

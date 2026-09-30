package com.nms.channel.webhook;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The webhook provider against a WireMock endpoint (private hosts allowed, so localhost is reachable). */
@TestPropertySource(properties = {
    "nms.webhook.enabled=true",
    "nms.webhook.signing-secret=" + WebhookTestSupport.SECRET,
    "nms.webhook.allow-private-hosts=true",
    "nms.worker.provider-timeout=1s"})
class WebhookDeliveryIT extends WebhookTestSupport {

    /** Submits a WEBHOOK notification to a new recipient whose URL is {@code url}; returns the delivery ID. */
    private UUID submitWebhook(String url) throws Exception {
        ObjectNode body = request("billing", webhookRecipient(url)).put("type", "ALERT").put("severity", "HIGH")
                .put("priority", "HIGH");
        body.putArray("channels").add("WEBHOOK");
        return submitAndGetDelivery(body);
    }

    private UUID submitWebhookAnswering(String path, int status) throws Exception {
        endpoint.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(status)));
        return submitWebhook(endpointUrl(path));
    }

    private void assertFailedAfterOneAttempt(UUID delivery, String failureClass) {
        assertThat(deliveryStatus(delivery)).isEqualTo("FAILED");
        assertThat(lastFailureClass(delivery)).isEqualTo(failureClass);
        assertThat(attempts(delivery)).isEqualTo(1);
    }

    private void assertRetryScheduled(UUID delivery, String failureClass) {
        assertThat(deliveryStatus(delivery)).isEqualTo("RETRY_SCHEDULED");
        assertThat(lastFailureClass(delivery)).isEqualTo(failureClass);
    }

    @Test
    void signedWebhookDelivered() throws Exception {
        endpoint.stubFor(post(urlEqualTo("/hooks/signed")).willReturn(ok()));
        UUID delivery = submitWebhook(endpointUrl("/hooks/signed"));
        var row = jdbc.sql("""
                SELECT d.notification_id, d.recipient_id, n.event_id FROM delivery d
                JOIN notification n ON n.id = d.notification_id WHERE d.id = ?
                """).param(delivery).query((rs, i) -> new String[] {
                    rs.getString(1), rs.getString(2), rs.getString(3)}).single();

        worker.runOnce(10);

        assertThat(deliveryStatus(delivery)).isEqualTo("SENT");
        List<LoggedRequest> requests = endpoint.findAll(postRequestedFor(urlEqualTo("/hooks/signed")));
        assertThat(requests).hasSize(1);
        LoggedRequest sent = requests.get(0);
        assertThat(sent.getHeader("Content-Type")).startsWith("application/json");
        assertThat(sent.getHeader("Idempotency-Key")).isEqualTo(delivery.toString());
        String timestamp = sent.getHeader("X-NMS-Timestamp");
        assertThat(timestamp).matches("\\d+");
        assertThat(sent.getHeader("X-NMS-Signature"))
                .isEqualTo(WebhookProvider.sign(SECRET, timestamp, sent.getBody()));

        JsonNode payload = mapper.readTree(new String(sent.getBody(), StandardCharsets.UTF_8));
        assertThat(payload.properties()).extracting(java.util.Map.Entry::getKey).containsExactly(
                "notificationId", "deliveryId", "eventId", "sourceSystem", "type", "severity", "priority",
                "recipientId", "subject", "body", "attempt", "sentAt");
        assertThat(payload.get("notificationId").asText()).isEqualTo(row[0]);
        assertThat(payload.get("deliveryId").asText()).isEqualTo(delivery.toString());
        assertThat(payload.get("eventId").asText()).isEqualTo(row[2]);
        assertThat(payload.get("sourceSystem").asText()).isEqualTo("billing");
        assertThat(payload.get("type").asText()).isEqualTo("ALERT");
        assertThat(payload.get("severity").asText()).isEqualTo("HIGH");
        assertThat(payload.get("priority").asText()).isEqualTo("HIGH");
        assertThat(payload.get("recipientId").asText()).isEqualTo(row[1]);
        assertThat(payload.get("subject").asText()).isEqualTo("Invoice ready");
        assertThat(payload.get("body").asText()).isEqualTo("Your invoice is ready.");
        assertThat(payload.get("attempt").asInt()).isEqualTo(1);
        assertThat(Instant.parse(payload.get("sentAt").asText()).getEpochSecond()).isEqualTo(Long.parseLong(timestamp));
    }

    @Test
    void serverErrorRetried() throws Exception {
        endpoint.stubFor(post(urlEqualTo("/hooks/503")).inScenario("503").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        endpoint.stubFor(post(urlEqualTo("/hooks/503")).inScenario("503").whenScenarioStateIs("recovered")
                .willReturn(ok()));
        UUID delivery = submitWebhook(endpointUrl("/hooks/503"));

        worker.runOnce(10);
        assertRetryScheduled(delivery, "TRANSIENT");
        makeDue(delivery);
        worker.runOnce(10);

        assertThat(deliveryStatus(delivery)).isEqualTo("SENT");
        assertThat(attempts(delivery)).isEqualTo(2);
        List<LoggedRequest> requests = endpoint.findAll(postRequestedFor(urlEqualTo("/hooks/503")));
        assertThat(requests).extracting(r -> r.getHeader("Idempotency-Key"))
                .containsExactly(delivery.toString(), delivery.toString());
    }

    @Test
    void unreachableEndpointRetried() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        UUID refused = submitWebhook("http://localhost:" + closedPort + "/hooks/refused");
        UUID unresolvable = submitWebhook("http://nms-webhook-test.invalid/hooks/unknown");

        worker.runOnce(10);

        assertRetryScheduled(refused, "TRANSIENT");
        assertRetryScheduled(unresolvable, "TRANSIENT");
    }

    @Test
    void slowEndpointTimesOut() throws Exception {
        endpoint.stubFor(post(urlEqualTo("/hooks/slow")).willReturn(ok().withFixedDelay(2500)));
        UUID delivery = submitWebhook(endpointUrl("/hooks/slow"));

        worker.runOnce(10);

        assertRetryScheduled(delivery, "TIMEOUT");
    }

    @Test
    void webhookRateLimitHonored() throws Exception {
        endpoint.stubFor(post(urlEqualTo("/hooks/429"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "30")));
        UUID delivery = submitWebhook(endpointUrl("/hooks/429"));

        worker.runOnce(10);

        assertRetryScheduled(delivery, "RATE_LIMITED");
        var times = jdbc.sql("SELECT last_attempt_at, next_attempt_at FROM delivery WHERE id = ?").param(delivery)
                .query((rs, i) -> new Timestamp[] {rs.getTimestamp(1), rs.getTimestamp(2)}).single();
        assertThat(Duration.between(times[0].toInstant(), times[1].toInstant())).isGreaterThanOrEqualTo(
                Duration.ofSeconds(30));
    }

    @Test
    void endpointRejectsCredentials() throws Exception {
        UUID unauthorized = submitWebhookAnswering("/hooks/401", 401);
        UUID forbidden = submitWebhookAnswering("/hooks/403", 403);

        worker.runOnce(10);

        assertFailedAfterOneAttempt(unauthorized, "AUTH_ERROR");
        assertFailedAfterOneAttempt(forbidden, "AUTH_ERROR");
    }

    @Test
    void endpointGone() throws Exception {
        UUID notFound = submitWebhookAnswering("/hooks/404", 404);
        UUID gone = submitWebhookAnswering("/hooks/410", 410);

        worker.runOnce(10);

        assertFailedAfterOneAttempt(notFound, "INVALID_RECIPIENT");
        assertFailedAfterOneAttempt(gone, "INVALID_RECIPIENT");
    }

    @Test
    void otherClientErrorNotRetried() throws Exception {
        UUID delivery = submitWebhookAnswering("/hooks/400", 400);

        worker.runOnce(10);

        assertFailedAfterOneAttempt(delivery, "PERMANENT_REJECTION");
    }

    @Test
    void redirectNotFollowed() throws Exception {
        endpoint.stubFor(post(urlEqualTo("/hooks/elsewhere")).willReturn(ok()));
        endpoint.stubFor(post(urlEqualTo("/hooks/302"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", endpointUrl("/hooks/elsewhere"))));
        UUID delivery = submitWebhook(endpointUrl("/hooks/302"));

        worker.runOnce(10);

        assertFailedAfterOneAttempt(delivery, "PERMANENT_REJECTION");
        endpoint.verify(0, postRequestedFor(urlEqualTo("/hooks/elsewhere")));
        endpoint.verify(0, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(anyUrl()));
    }
}

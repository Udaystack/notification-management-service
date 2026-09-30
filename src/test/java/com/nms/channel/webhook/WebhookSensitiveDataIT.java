package com.nms.channel.webhook;

import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "nms.webhook.enabled=true",
    "nms.webhook.signing-secret=" + WebhookTestSupport.SECRET,
    "nms.webhook.allow-private-hosts=true"})
class WebhookSensitiveDataIT extends WebhookTestSupport {

    private UUID submitWebhook(String url) throws Exception {
        ObjectNode body = request("billing", webhookRecipient(url));
        body.putArray("channels").add("WEBHOOK");
        return idOf(submit(BILLING_KEY, newKey(), body).andExpect(status().isAccepted()));
    }

    private List<String> auditRows(UUID notificationId) {
        return jdbc.sql("SELECT event_type || ' ' || coalesce(reason_code, '') || ' ' || details::text "
                + "FROM audit_event WHERE notification_id = ? ORDER BY id").param(notificationId)
                .query(String.class).list();
    }

    @Test
    void webhookUrlMasked() throws Exception {
        // Intake only: the URL is never called, so the external host is never contacted.
        UUID id = submitWebhook("https://user:pw@hooks.example.com:8443/notify?token=abc");

        String queued = jdbc.sql("SELECT details->>'address' FROM audit_event WHERE notification_id = ? "
                + "AND event_type = 'DELIVERY_QUEUED'").param(id).query(String.class).single();
        assertThat(queued).isEqualTo("https://hooks.example.com/***");
        mvc.perform(get("/api/v1/notifications/" + id).header("X-API-Key", BILLING_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries[0].channel").value("WEBHOOK"))
                .andExpect(jsonPath("$.deliveries[0].address").value("https://hooks.example.com/***"));
        assertThat(auditRows(id)).noneMatch(row -> row.contains("token=abc") || row.contains("user:pw")
                || row.contains("8443") || row.contains("/notify"));
    }

    @Test
    void signingSecretNeverLogged(CapturedOutput output) throws Exception {
        endpoint.stubFor(post(urlPathEqualTo("/hooks/secret")).willReturn(ok()));
        UUID id = submitWebhook(endpointUrl("/hooks/secret?token=abc"));

        worker.runOnce(10);

        List<String> signatures = endpoint.findAll(postRequestedFor(urlPathEqualTo("/hooks/secret"))).stream()
                .map(r -> r.getHeader("X-NMS-Signature")).toList();
        assertThat(signatures).hasSize(1);
        String signature = signatures.get(0);
        assertThat(jdbc.sql("SELECT status FROM delivery WHERE notification_id = ?").param(id).query(String.class)
                .single()).isEqualTo("SENT");
        assertThat(output.getAll()).doesNotContain(SECRET, signature, signature.substring("sha256=".length()),
                "token=abc");
        List<String> audit = auditRows(id);
        assertThat(audit).extracting(row -> row.split(" ")[0])
                .contains("DELIVERY_ATTEMPTED", "DELIVERY_SUCCEEDED");
        assertThat(audit).noneMatch(row -> row.contains(SECRET) || row.contains(signature) || row.contains("token=abc"));
        assertThat(audit).filteredOn(row -> row.startsWith("DELIVERY_SUCCEEDED"))
                .allMatch(row -> row.contains("\"address\": \"http://localhost/***\""));
    }
}

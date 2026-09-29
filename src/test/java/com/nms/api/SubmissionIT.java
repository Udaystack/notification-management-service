package com.nms.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubmissionIT extends ApiTestSupport {

    @Test
    void validNotificationIsAccepted() throws Exception {
        var result = submit(BILLING_KEY, newKey(), request("billing", "cust-1001"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        UUID id = idOf(result);
        result.andExpect(header().string("Location", "/api/v1/notifications/" + id))
                .andExpect(jsonPath("$.statusUrl").value("/api/v1/notifications/" + id));

        assertThat(count("SELECT count(*) FROM notification WHERE id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM delivery WHERE notification_id = ?", id)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT event_type FROM audit_event WHERE notification_id = ? ORDER BY id")
                .param(id).query(String.class).list())
                .containsExactly("NOTIFICATION_ACCEPTED", "ROUTING_DECIDED", "DELIVERY_QUEUED");
    }

    @Test
    void processingIsAsynchronous() throws Exception {
        UUID id = idOf(submit(BILLING_KEY, newKey(), request("billing", "cust-1001")).andExpect(status().isAccepted()));
        assertThat(jdbc.sql("SELECT status FROM delivery WHERE notification_id = ?").param(id)
                .query(String.class).list()).containsOnly("PENDING");
        assertThat(count("SELECT count(*) FROM delivery WHERE notification_id = ? AND attempt_count > 0", id)).isZero();
    }

    @Test
    void missingRecipients() throws Exception {
        ObjectNode body = request("billing");
        submit(BILLING_KEY, newKey(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("recipients"));
    }

    @Test
    void unknownEnumValue() throws Exception {
        submit(BILLING_KEY, newKey(), request("billing", "cust-1001").put("severity", "URGENT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("severity"))
                .andExpect(jsonPath("$.errors[0].message").value("must be one of [LOW, MEDIUM, HIGH, CRITICAL]"));
    }

    @Test
    void expiryBeforeSchedule() throws Exception {
        String at = Instant.now().plus(1, ChronoUnit.DAYS).toString();
        submit(BILLING_KEY, newKey(), request("billing", "cust-1001").put("scheduledAt", at).put("expiresAt", at))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("expiresAt"))
                .andExpect(jsonPath("$.errors[0].message").value("must be after scheduledAt"));
    }

    @Test
    void alreadyExpired() throws Exception {
        String past = Instant.now().minus(1, ChronoUnit.MINUTES).toString();
        submit(BILLING_KEY, newKey(), request("billing", "cust-1001").put("expiresAt", past))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("expiresAt"))
                .andExpect(jsonPath("$.errors[0].message").value("is in the past; the notification has already expired"));
    }

    @Test
    void missingIdempotencyKey() throws Exception {
        submit(BILLING_KEY, null, request("billing", "cust-1001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
    }

    @Test
    void recipientLimitExceeded() throws Exception {
        ObjectNode body = request("billing");
        ArrayNode recipients = body.putArray("recipients");
        for (int i = 0; i < 101; i++) {
            recipients.add("cust-" + i);
        }
        submit(BILLING_KEY, newKey(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("must contain at most 100 recipients"));
    }

    @Test
    void rejectionLeavesOnlyAnAuditTrace() throws Exception {
        String marker = "rejection-trace-" + UUID.randomUUID();
        int notificationsBefore = count("SELECT count(*) FROM notification");
        int deliveriesBefore = count("SELECT count(*) FROM delivery");
        long lastAudit = jdbc.sql("SELECT coalesce(max(id), 0) FROM audit_event").query(Long.class).single();

        ObjectNode body = request("billing").put("subject", marker).put("body", marker);
        submit(BILLING_KEY, newKey(), body).andExpect(status().isBadRequest());

        assertThat(count("SELECT count(*) FROM notification")).isEqualTo(notificationsBefore);
        assertThat(count("SELECT count(*) FROM delivery")).isEqualTo(deliveriesBefore);
        List<JsonNode> rejections = jdbc.sql("""
                SELECT details::text FROM audit_event
                WHERE id > ? AND event_type = 'NOTIFICATION_REJECTED' AND reason_code = 'VALIDATION_FAILED'
                  AND notification_id IS NULL AND source_system = 'billing'
                """).param(lastAudit).query(String.class).list().stream().map(this::json).toList();
        assertThat(rejections).hasSize(1);
        assertThat(rejections.get(0).toString()).doesNotContain(marker);
        assertThat(rejections.get(0).get("invalidFields").get(0).asText()).isEqualTo("recipients");
    }

    @Test
    void futureSchedule() throws Exception {
        Instant scheduledAt = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        UUID id = idOf(submit(BILLING_KEY, newKey(), request("billing", "cust-1001")
                .put("scheduledAt", scheduledAt.toString())).andExpect(status().isAccepted()));
        assertThat(jdbc.sql("SELECT status FROM delivery WHERE notification_id = ?").param(id)
                .query(String.class).single()).isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT next_attempt_at FROM delivery WHERE notification_id = ?").param(id)
                .query(java.sql.Timestamp.class).single().toInstant()).isEqualTo(scheduledAt);
    }

    @Test
    void noDeliverableRecipientAtAll() throws Exception {
        long lastAudit = jdbc.sql("SELECT coalesce(max(id), 0) FROM audit_event").query(Long.class).single();
        ObjectNode body = request("billing", "cust-1003", "unknown-recipient");
        body.putArray("channels").add("PUSH");
        submit(BILLING_KEY, newKey(), body)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("NO_ELIGIBLE_CHANNEL"));
        assertThat(count("""
                SELECT count(*) FROM audit_event WHERE id > ? AND event_type = 'NOTIFICATION_REJECTED'
                  AND reason_code = 'NO_ELIGIBLE_CHANNEL'
                """, lastAudit)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE id > ? AND notification_id IS NOT NULL", lastAudit))
                .isZero();
    }

    @Test
    void decisionReasonsRecorded() throws Exception {
        ObjectNode body = request("billing", "cust-1004").put("type", "ALERT").put("severity", "CRITICAL");
        body.remove("channels");
        UUID id = idOf(submit(BILLING_KEY, newKey(), body).andExpect(status().isAccepted()));

        JsonNode details = json(jdbc.sql("""
                SELECT details::text FROM audit_event WHERE notification_id = ? AND event_type = 'ROUTING_DECIDED'
                """).param(id).query(String.class).single());
        assertThat(details.get("added").get("SMS").asText()).isEqualTo("SEVERITY_ESCALATION");
        assertThat(details.get("removed").get("PUSH").asText()).isEqualTo("RECIPIENT_OPT_OUT");
    }

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

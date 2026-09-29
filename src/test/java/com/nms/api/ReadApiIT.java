package com.nms.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nms.delivery.DeliveryTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

class ReadApiIT extends DeliveryTestSupport {

    private ResultActions getAs(String apiKey, String path) throws Exception {
        return mvc.perform(get(path).header("X-API-Key", apiKey));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    @Test
    void statusOfAnInProgressNotification() throws Exception {
        UUID id = idOf(submit(BILLING_KEY, newKey(), request("billing", "cust-1001", "cust-2001")));
        worker.runOnce(10);

        JsonNode view = body(getAs(BILLING_KEY, "/api/v1/notifications/" + id).andExpect(status().isOk()));
        assertThat(view.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(view.get("selectedChannels")).extracting(JsonNode::asText).containsExactly("EMAIL");
        List<String> statuses = new ArrayList<>();
        view.get("deliveries").forEach(d -> {
            statuses.add(d.get("status").asText());
            assertThat(d.has("lastAttemptAt")).isTrue();
            assertThat(d.has("nextAttemptAt")).isTrue();
            assertThat(d.has("completedAt")).isTrue();
        });
        assertThat(statuses).containsExactlyInAnyOrder("SENT", "RETRY_SCHEDULED");
        assertThat(view.has("subject")).isFalse();
        assertThat(view.has("body")).isFalse();
    }

    @Test
    void addressesMasked() throws Exception {
        UUID id = idOf(submit(BILLING_KEY, newKey(), request("billing", "cust-1001")));
        getAs(BILLING_KEY, "/api/v1/notifications/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries[0].address").value("j***@example.com"));
    }

    @Test
    void unknownNotification() throws Exception {
        getAs(BILLING_KEY, "/api/v1/notifications/" + UUID.randomUUID()).andExpect(status().isNotFound());
        getAs(BILLING_KEY, "/api/v1/notifications/not-a-uuid").andExpect(status().isNotFound());
    }

    @Test
    void notificationOwnedByAnotherSourceSystem() throws Exception {
        UUID id = idOf(submit(TRADING_KEY, newKey(), request("trading", "cust-1001")));
        JsonNode othersNotification = body(getAs(BILLING_KEY, "/api/v1/notifications/" + id)
                .andExpect(status().isNotFound()));
        JsonNode nonexistent = body(getAs(BILLING_KEY, "/api/v1/notifications/" + UUID.randomUUID())
                .andExpect(status().isNotFound()));
        // Identical apart from the echoed request path: existence is not revealed.
        ((com.fasterxml.jackson.databind.node.ObjectNode) othersNotification).remove("instance");
        ((com.fasterxml.jackson.databind.node.ObjectNode) nonexistent).remove("instance");
        assertThat(othersNotification).isEqualTo(nonexistent);
    }

    @Test
    void auditForUnknownNotification() throws Exception {
        getAs(BILLING_KEY, "/api/v1/notifications/" + UUID.randomUUID() + "/audit").andExpect(status().isNotFound());
    }

    @Test
    void auditOwnedByAnotherSourceSystem() throws Exception {
        UUID id = idOf(submit(TRADING_KEY, newKey(), request("trading", "cust-1001")));
        getAs(BILLING_KEY, "/api/v1/notifications/" + id + "/audit").andExpect(status().isNotFound());
    }

    @Test
    void fullLifecycleIsAuditable() throws Exception {
        UUID id = idOf(submit(BILLING_KEY, newKey(), request("billing", "cust-2007")));
        UUID delivery = jdbc.sql("SELECT id FROM delivery WHERE notification_id = ?").param(id)
                .query(UUID.class).single();
        worker.runOnce(10);
        makeDue(delivery);
        worker.runOnce(10);

        JsonNode audit = body(getAs(BILLING_KEY, "/api/v1/notifications/" + id + "/audit").andExpect(status().isOk()));
        assertThat(audit.get("notificationId").asText()).isEqualTo(id.toString());
        assertThat(audit.get("events")).extracting(e -> e.get("eventType").asText()).containsExactly(
                "NOTIFICATION_ACCEPTED", "ROUTING_DECIDED", "DELIVERY_QUEUED", "DELIVERY_ATTEMPTED",
                "RETRY_SCHEDULED", "DELIVERY_ATTEMPTED", "DELIVERY_SUCCEEDED");
    }
}

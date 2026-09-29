package com.nms.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/** Suppression as seen through the status API. */
@TestPropertySource(properties = "nms.dedup.enabled=true")
class SuppressedStatusApiIT extends DedupTestSupport {

    private ResultActions getAs(String apiKey, UUID id) throws Exception {
        return mvc.perform(get("/api/v1/notifications/" + id).header("X-API-Key", apiKey));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    @Test
    void suppressedDeliveryShowsItsOrigin() throws Exception {
        String eventId = newEventId();
        UUID first = idOf(submit(BILLING_KEY, newKey(), request("billing", eventId, List.of("EMAIL"), "cust-1001")));
        UUID second = idOf(submit(BILLING_KEY, newKey(),
                request("billing", eventId, List.of("EMAIL"), "cust-1001", "cust-1002")));
        UUID originalDelivery = onlyDelivery(first).id();

        JsonNode view = body(getAs(BILLING_KEY, second).andExpect(status().isOk()));
        JsonNode suppressed = view.get("deliveries").get(0);
        JsonNode created = view.get("deliveries").get(1);
        assertThat(suppressed.get("recipientId").asText()).isEqualTo("cust-1001");
        assertThat(suppressed.get("status").asText()).isEqualTo("SUPPRESSED");
        assertThat(suppressed.get("suppressedBy").get("notificationId").asText()).isEqualTo(first.toString());
        assertThat(suppressed.get("suppressedBy").get("deliveryId").asText()).isEqualTo(originalDelivery.toString());
        assertThat(suppressed.get("attemptCount").asInt()).isZero();
        assertThat(suppressed.get("nextAttemptAt").isNull()).isTrue();
        assertThat(suppressed.get("completedAt").asText()).isEqualTo(view.get("createdAt").asText());
        assertThat(created.get("status").asText()).isEqualTo("PENDING");
        assertThat(created.has("suppressedBy")).isTrue();
        assertThat(created.get("suppressedBy").isNull()).isTrue();

        // The referenced original belongs to the same source system: visible to billing, hidden from trading.
        getAs(BILLING_KEY, first).andExpect(status().isOk());
        getAs(TRADING_KEY, first).andExpect(status().isNotFound());
    }

    @Test
    void fullySuppressedNotification() throws Exception {
        ObjectNode body = request("billing", newEventId(), List.of("EMAIL"), "cust-1001");
        submit(BILLING_KEY, newKey(), body);
        UUID second = idOf(submit(BILLING_KEY, newKey(), body)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SUPPRESSED")));

        getAs(BILLING_KEY, second)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPPRESSED"))
                .andExpect(jsonPath("$.deliveries[0].status").value("SUPPRESSED"));
    }
}

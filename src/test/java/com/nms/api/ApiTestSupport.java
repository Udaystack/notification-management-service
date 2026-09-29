package com.nms.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nms.IntegrationTestBase;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Helpers for API-level integration tests. */
@AutoConfigureMockMvc
public abstract class ApiTestSupport extends IntegrationTestBase {

    public static final String BILLING_KEY = "billing-demo-key-7f3a9c2e5b8d4f1a";
    public static final String TRADING_KEY = "trading-demo-key-2c8e6a1f9d4b7e3c";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper mapper;

    @Autowired
    protected JdbcClient jdbc;

    protected ObjectNode request(String sourceSystem, String... recipients) {
        ObjectNode body = mapper.createObjectNode()
                .put("sourceSystem", sourceSystem)
                .put("eventId", "EVT-" + UUID.randomUUID())
                .put("type", "TRANSACTIONAL")
                .put("severity", "MEDIUM")
                .put("priority", "NORMAL")
                .put("subject", "Invoice ready")
                .put("body", "Your invoice is ready.");
        var list = body.putArray("recipients");
        for (String r : recipients) {
            list.add(r);
        }
        body.putArray("channels").add("EMAIL");
        return body;
    }

    protected ResultActions submit(String apiKey, String idempotencyKey, Object body) throws Exception {
        var builder = post("/api/v1/notifications")
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body instanceof String s ? s : mapper.writeValueAsString(body));
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(builder);
    }

    protected static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    protected UUID idOf(ResultActions result) throws Exception {
        return UUID.fromString(mapper.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asText());
    }

    protected int count(String sql, Object... params) {
        var spec = jdbc.sql(sql);
        for (int i = 0; i < params.length; i++) {
            spec = spec.param(i + 1, params[i]);
        }
        return spec.query(Integer.class).single();
    }
}

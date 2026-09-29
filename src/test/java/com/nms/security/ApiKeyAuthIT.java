package com.nms.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nms.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ApiKeyAuthIT extends IntegrationTestBase {

    private static final String ANY_API_PATH = "/api/v1/notifications/00000000-0000-0000-0000-000000000000";

    @Autowired
    MockMvc mvc;

    @Test
    void missingOrInvalidKey() throws Exception {
        mvc.perform(get(ANY_API_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(get(ANY_API_PATH).header(ApiKeyAuthFilter.HEADER, "not-a-real-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void inactiveKeyIsUnauthorized() throws Exception {
        mvc.perform(get(ANY_API_PATH).header(ApiKeyAuthFilter.HEADER, "legacy-demo-key-5a1d8f3c7e2b9a6d"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validKeyPassesAuthentication() throws Exception {
        mvc.perform(get(ANY_API_PATH).header(ApiKeyAuthFilter.HEADER, "billing-demo-key-7f3a9c2e5b8d4f1a"))
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    @Test
    void sourceSystemMismatch() throws Exception {
        String body = """
                {"sourceSystem": "trading", "recipients": []}
                """;
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/notifications")
                        .header(ApiKeyAuthFilter.HEADER, "billing-demo-key-7f3a9c2e5b8d4f1a")
                        .header("Idempotency-Key", "k-mismatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("SOURCE_SYSTEM_MISMATCH"));
    }

    @Test
    void healthIsOpenWithoutKey() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}

package com.nms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nms.delivery.DeliveryTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class LoggingIT extends DeliveryTestSupport {

    @Test
    void messageContentExcluded(CapturedOutput output) throws Exception {
        String body = "Your balance is $12,345";
        String subject = "Balance " + UUID.randomUUID();
        // Exercise accept, retry, permanent failure, auth error, and success paths.
        submit(BILLING_KEY, newKey(), request("billing", "cust-1001", "cust-2001", "cust-2005", "cust-2006")
                .put("subject", subject).put("body", body));
        submit(BILLING_KEY, newKey(), request("billing").put("subject", subject).put("body", body));
        worker.runOnce(10);

        assertThat(output.getAll()).doesNotContain(body, subject, "jane.doe@example.com", BILLING_KEY);
    }

    @Test
    void correlationIdIsEchoedOrGenerated() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Correlation-Id", "req-123"))
                .andExpect(header().string("X-Correlation-Id", "req-123"));
        mvc.perform(get("/api/v1/notifications/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Correlation-Id"));
        mvc.perform(get("/actuator/health").header("X-Correlation-Id", "bad value\n{\"x\":1}"))
                .andExpect(result -> assertThat(result.getResponse().getHeader("X-Correlation-Id"))
                        .matches("[0-9a-f-]{36}"));
    }

    @Test
    void workerLogsCarryNotificationAndDeliveryIds(CapturedOutput output) throws Exception {
        UUID missing = submitTo("cust-1002");
        jdbc.sql("DELETE FROM recipient_channel WHERE recipient_id = 'cust-1002' AND channel = 'EMAIL'").update();
        try {
            worker.runOnce(10);
            assertThat(output.getAll()).containsPattern("\"deliveryId\":\"" + missing + "\"");
        } finally {
            jdbc.sql("""
                    INSERT INTO recipient_channel (recipient_id, channel, address, opted_out)
                    VALUES ('cust-1002', 'EMAIL', 'john.smith@example.com', FALSE)
                    """).update();
        }
    }
}

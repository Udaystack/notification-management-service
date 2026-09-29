package com.nms.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nms.delivery.DeliveryTestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SensitiveDataIT extends DeliveryTestSupport {

    private static final List<String> SECRETS = List.of(
            "billing-demo-key-7f3a9c2e5b8d4f1a", "trading-demo-key-2c8e6a1f9d4b7e3c",
            "legacy-demo-key-5a1d8f3c7e2b9a6d");

    @Test
    void messageContentExcluded() throws Exception {
        String subject = "Statement " + UUID.randomUUID();
        String body = "Your balance is $12,345";
        ObjectNode request = request("billing", "cust-1001", "cust-1004", "cust-2001", "cust-2005")
                .put("subject", subject).put("body", body).put("severity", "CRITICAL");
        submit(BILLING_KEY, newKey(), request);
        submit(BILLING_KEY, newKey(), request("billing").put("subject", subject).put("body", body)); // 400
        worker.runOnce(20);

        List<String> fullAddresses = jdbc.sql("SELECT address FROM recipient_channel").query(String.class).list();
        List<String> rows = jdbc.sql("""
                SELECT concat_ws('|', event_type, reason_code, source_system, details::text) FROM audit_event
                """).query(String.class).list();

        assertThat(rows).isNotEmpty().allSatisfy(row -> {
            assertThat(row).doesNotContain(subject, body);
            fullAddresses.forEach(address -> assertThat(row).doesNotContain(address));
            SECRETS.forEach(secret -> assertThat(row).doesNotContain(secret));
        });
    }

    @Test
    void addressMasked() throws Exception {
        UUID id = idOf(submit(BILLING_KEY, newKey(), request("billing", "cust-1001")));
        String queued = jdbc.sql("""
                SELECT details::text FROM audit_event WHERE notification_id = ? AND event_type = 'DELIVERY_QUEUED'
                """).param(id).query(String.class).single();
        assertThat(queued).contains("j***@example.com").doesNotContain("jane.doe@example.com");
    }
}

package com.nms.delivery;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nms.api.ApiTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/** Delivery tests share one database; other tests' deliveries are parked so claims only see this test's rows. */
public abstract class DeliveryTestSupport extends ApiTestSupport {

    @Autowired
    protected DeliveryWorker worker;

    @BeforeEach
    protected void parkOtherDeliveries() {
        jdbc.sql("""
                UPDATE delivery SET next_attempt_at = 'infinity', locked_until = 'infinity'
                WHERE status IN ('PENDING', 'RETRY_SCHEDULED', 'IN_FLIGHT')
                """).update();
    }

    protected UUID submitAndGetDelivery(ObjectNode body) throws Exception {
        UUID notificationId = idOf(submit(BILLING_KEY, newKey(), body));
        return jdbc.sql("SELECT id FROM delivery WHERE notification_id = ?").param(notificationId)
                .query(UUID.class).single();
    }

    protected UUID submitTo(String recipientId) throws Exception {
        return submitAndGetDelivery(request("billing", recipientId));
    }

    protected void makeDue(UUID deliveryId) {
        jdbc.sql("UPDATE delivery SET next_attempt_at = now() - interval '1 second' WHERE id = ?")
                .param(deliveryId).update();
    }

    protected String deliveryStatus(UUID deliveryId) {
        return jdbc.sql("SELECT status FROM delivery WHERE id = ?").param(deliveryId).query(String.class).single();
    }

    protected int attempts(UUID deliveryId) {
        return jdbc.sql("SELECT attempt_count FROM delivery WHERE id = ?").param(deliveryId)
                .query(Integer.class).single();
    }

    protected java.util.List<String> auditTypes(UUID deliveryId) {
        return jdbc.sql("SELECT event_type FROM audit_event WHERE delivery_id = ? ORDER BY id").param(deliveryId)
                .query(String.class).list();
    }

    protected String auditReason(UUID deliveryId, String eventType) {
        return jdbc.sql("SELECT reason_code FROM audit_event WHERE delivery_id = ? AND event_type = ?")
                .param(deliveryId).param(eventType).query(String.class).single();
    }
}

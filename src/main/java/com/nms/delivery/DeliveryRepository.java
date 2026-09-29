package com.nms.delivery;

import com.nms.common.domain.DeliveryStatus;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryRepository {

    private final JdbcClient jdbc;

    DeliveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPending(NewDelivery delivery, Instant now) {
        jdbc.sql("""
                INSERT INTO delivery (id, notification_id, recipient_id, channel, address_masked, status,
                    next_attempt_at, created_at)
                VALUES (:id, :notificationId, :recipientId, :channel, :addressMasked, :status, :nextAttemptAt, :now)
                """)
                .param("id", delivery.id())
                .param("notificationId", delivery.notificationId())
                .param("recipientId", delivery.recipientId())
                .param("channel", delivery.channel().name())
                .param("addressMasked", delivery.addressMasked())
                .param("status", DeliveryStatus.PENDING.name())
                .param("nextAttemptAt", Timestamp.from(delivery.nextAttemptAt()))
                .param("now", Timestamp.from(now))
                .update();
    }
}

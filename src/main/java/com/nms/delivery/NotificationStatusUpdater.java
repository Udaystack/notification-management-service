package com.nms.delivery;

import com.nms.common.domain.DeliveryStatus;
import com.nms.common.domain.NotificationStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Recomputes the cached overall status from the delivery states, in the caller's transaction. */
@Component
class NotificationStatusUpdater {

    private final JdbcClient jdbc;

    NotificationStatusUpdater(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recompute(UUID notificationId, Instant now) {
        List<DeliveryStatus> statuses = jdbc.sql("SELECT status FROM delivery WHERE notification_id = :id")
                .param("id", notificationId)
                .query((rs, row) -> DeliveryStatus.valueOf(rs.getString(1)))
                .list();
        NotificationStatus status = NotificationStatusDeriver.derive(statuses);
        jdbc.sql("""
                UPDATE notification SET status = :status, updated_at = :now, version = version + 1
                WHERE id = :id AND status <> :status
                """)
                .param("status", status.name())
                .param("now", Timestamp.from(now))
                .param("id", notificationId)
                .update();
    }
}

package com.nms.delivery;

import com.nms.common.domain.DeliveryStatus;
import com.nms.common.domain.NotificationStatus;
import java.util.Collection;

/** Derives a notification's overall status from its delivery states (see the notification-status spec). */
public final class NotificationStatusDeriver {

    private NotificationStatusDeriver() {
    }

    public static NotificationStatus derive(Collection<DeliveryStatus> deliveries) {
        if (deliveries.isEmpty()) {
            throw new IllegalArgumentException("A notification always has at least one delivery");
        }
        if (deliveries.stream().allMatch(s -> s == DeliveryStatus.PENDING)) {
            return NotificationStatus.ACCEPTED;
        }
        if (!deliveries.stream().allMatch(DeliveryStatus::isTerminal)) {
            return NotificationStatus.IN_PROGRESS;
        }
        boolean anySent = deliveries.contains(DeliveryStatus.SENT);
        boolean anyFailed = deliveries.contains(DeliveryStatus.FAILED);
        boolean anyExpired = deliveries.contains(DeliveryStatus.EXPIRED);
        if (anySent) {
            return anyFailed || anyExpired ? NotificationStatus.PARTIALLY_DELIVERED : NotificationStatus.COMPLETED;
        }
        return anyFailed ? NotificationStatus.FAILED : NotificationStatus.EXPIRED;
    }
}

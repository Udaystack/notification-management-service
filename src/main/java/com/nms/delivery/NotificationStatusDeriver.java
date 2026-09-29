package com.nms.delivery;

import com.nms.common.domain.DeliveryStatus;
import com.nms.common.domain.NotificationStatus;
import java.util.Collection;
import java.util.List;

/** Derives a notification's overall status from its delivery states (see the notification-status spec). */
public final class NotificationStatusDeriver {

    private NotificationStatusDeriver() {
    }

    /** Suppressed deliveries are ignored, except that a notification whose deliveries are all suppressed is SUPPRESSED. */
    public static NotificationStatus derive(Collection<DeliveryStatus> deliveries) {
        if (deliveries.isEmpty()) {
            throw new IllegalArgumentException("A notification always has at least one delivery");
        }
        List<DeliveryStatus> relevant = deliveries.stream().filter(s -> s != DeliveryStatus.SUPPRESSED).toList();
        if (relevant.isEmpty()) {
            return NotificationStatus.SUPPRESSED;
        }
        if (relevant.stream().allMatch(s -> s == DeliveryStatus.PENDING)) {
            return NotificationStatus.ACCEPTED;
        }
        if (!relevant.stream().allMatch(DeliveryStatus::isTerminal)) {
            return NotificationStatus.IN_PROGRESS;
        }
        boolean anySent = relevant.contains(DeliveryStatus.SENT);
        boolean anyFailed = relevant.contains(DeliveryStatus.FAILED);
        boolean anyExpired = relevant.contains(DeliveryStatus.EXPIRED);
        if (anySent) {
            return anyFailed || anyExpired ? NotificationStatus.PARTIALLY_DELIVERED : NotificationStatus.COMPLETED;
        }
        return anyFailed ? NotificationStatus.FAILED : NotificationStatus.EXPIRED;
    }
}

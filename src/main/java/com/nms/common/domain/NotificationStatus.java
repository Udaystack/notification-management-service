package com.nms.common.domain;

public enum NotificationStatus {
    ACCEPTED,
    IN_PROGRESS,
    COMPLETED,
    PARTIALLY_DELIVERED,
    FAILED,
    EXPIRED,
    /** Every delivery was suppressed as a duplicate event. */
    SUPPRESSED
}

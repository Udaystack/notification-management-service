package com.nms.dedup;

import java.util.UUID;

/** The deliverable delivery that a suppressed delivery duplicates. */
public record OriginalDelivery(UUID notificationId, UUID deliveryId) {}

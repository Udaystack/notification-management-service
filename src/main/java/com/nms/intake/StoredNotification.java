package com.nms.intake;

import com.nms.common.domain.NotificationStatus;
import java.util.UUID;

/** The minimum needed to answer an idempotent replay or conflict. */
public record StoredNotification(UUID id, String requestHash, NotificationStatus status) {}

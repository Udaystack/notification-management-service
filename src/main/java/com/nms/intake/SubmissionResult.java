package com.nms.intake;

import com.nms.common.domain.NotificationStatus;
import java.util.UUID;

/** @param replay {@code true} for an idempotent replay of an earlier submission */
public record SubmissionResult(UUID id, NotificationStatus status, boolean replay) {}

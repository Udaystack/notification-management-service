package com.nms.api;

import com.nms.common.domain.NotificationStatus;
import java.util.UUID;

public record SubmissionResponse(UUID id, NotificationStatus status, String statusUrl) {}

package com.nms.intake;

import com.nms.common.domain.Channel;
import com.nms.common.domain.NotificationType;
import com.nms.common.domain.Priority;
import com.nms.common.domain.Severity;
import java.time.Instant;
import java.util.List;

/** A shape-valid submission, converted to domain types. */
public record SubmitCommand(
        String sourceSystem,
        String eventId,
        NotificationType type,
        Severity severity,
        Priority priority,
        List<String> recipients,
        List<Channel> channels,
        String subject,
        String body,
        Instant scheduledAt,
        Instant expiresAt) {}

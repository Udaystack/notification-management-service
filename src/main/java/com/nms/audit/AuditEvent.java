package com.nms.audit;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** A stored audit event as returned by the audit API. */
public record AuditEvent(
        long id,
        UUID notificationId,
        UUID deliveryId,
        String eventType,
        String reasonCode,
        JsonNode details,
        Instant occurredAt) {}

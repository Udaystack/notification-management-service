package com.nms.api;

import com.nms.audit.AuditEvent;
import java.util.List;
import java.util.UUID;

public record AuditHistoryView(UUID notificationId, List<AuditEvent> events) {}

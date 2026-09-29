package com.nms.api;

import com.nms.audit.AuditRepository;
import com.nms.intake.NotificationIntakeService;
import com.nms.intake.SubmissionResult;
import com.nms.security.SourceSystemContext;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final NotificationIntakeService intake;
    private final NotificationQueryRepository queries;
    private final AuditRepository auditRepository;

    NotificationController(
            NotificationIntakeService intake, NotificationQueryRepository queries, AuditRepository auditRepository) {
        this.intake = intake;
        this.queries = queries;
        this.auditRepository = auditRepository;
    }

    /** {@code 202} for a new notification, {@code 200} for an idempotent replay. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SubmissionResponse> submit(
            @RequestAttribute(SourceSystemContext.ATTRIBUTE) String sourceSystem,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody(required = false) String body) {
        SubmissionResult result = intake.submit(sourceSystem, idempotencyKey, body);
        String statusUrl = "/api/v1/notifications/" + result.id();
        return ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .location(URI.create(statusUrl))
                .body(new SubmissionResponse(result.id(), result.status(), statusUrl));
    }

    @GetMapping("/{id}")
    public NotificationView status(
            @RequestAttribute(SourceSystemContext.ATTRIBUTE) String sourceSystem, @PathVariable String id) {
        return queries.find(parse(id), sourceSystem).orElseThrow(NotificationNotFoundException::new);
    }

    @GetMapping("/{id}/audit")
    public AuditHistoryView audit(
            @RequestAttribute(SourceSystemContext.ATTRIBUTE) String sourceSystem, @PathVariable String id) {
        UUID notificationId = parse(id);
        if (!queries.exists(notificationId, sourceSystem)) {
            throw new NotificationNotFoundException();
        }
        return new AuditHistoryView(notificationId, auditRepository.findForNotification(notificationId, sourceSystem));
    }

    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new NotificationNotFoundException();
        }
    }
}

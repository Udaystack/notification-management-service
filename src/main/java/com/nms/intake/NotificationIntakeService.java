package com.nms.intake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nms.audit.AuditDetails;
import com.nms.audit.AuditService;
import com.nms.common.domain.AuditEventType;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accepts a submission. Order of checks: source-system match ({@code 403}), shape validation ({@code 400}),
 * idempotency lookup ({@code 200} replay / {@code 409}), time-based validation ({@code 400}), then the intake
 * transaction ({@code 202}, or {@code 422} when no recipient has an eligible channel). Every rejection is audited.
 */
@Service
public class NotificationIntakeService {

    static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    static final String SOURCE_SYSTEM_MISMATCH = "SOURCE_SYSTEM_MISMATCH";
    static final String IDEMPOTENCY_KEY_CONFLICT = "IDEMPOTENCY_KEY_CONFLICT";
    static final String NO_ELIGIBLE_CHANNEL = "NO_ELIGIBLE_CHANNEL";

    public static final String ACCEPTED_METRIC = "nms.notifications.accepted";
    public static final String REJECTED_METRIC = "nms.notifications.rejected";

    private final ObjectMapper objectMapper;
    private final RequestValidator validator;
    private final NotificationRepository notifications;
    private final List<IntakeStep> steps;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;

    NotificationIntakeService(
            ObjectMapper objectMapper,
            RequestValidator validator,
            NotificationRepository notifications,
            List<IntakeStep> steps,
            AuditService audit,
            TransactionTemplate tx,
            Clock clock,
            MeterRegistry meters) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.notifications = notifications;
        this.steps = steps;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
    }

    public SubmissionResult submit(String authenticatedSourceSystem, String idempotencyKey, String rawBody) {
        ObjectNode body = parse(authenticatedSourceSystem, rawBody);

        JsonNode declared = body.get("sourceSystem");
        if (declared != null && declared.isTextual() && !declared.asText().equals(authenticatedSourceSystem)) {
            String shown = declared.asText().length() > 64 ? declared.asText().substring(0, 64) : declared.asText();
            reject(authenticatedSourceSystem, HttpStatus.FORBIDDEN, SOURCE_SYSTEM_MISMATCH,
                    "sourceSystem does not match the authenticated source system", List.of(),
                    AuditDetails.create().declaredSourceSystem(shown));
        }

        RequestValidator.Result validation = validator.validate(body, idempotencyKey);
        if (!validation.isValid()) {
            rejectInvalid(authenticatedSourceSystem, validation.errors());
        }
        SubmitCommand command = validation.command();
        String requestHash = RequestHasher.hash(command);

        Optional<StoredNotification> existing = notifications.findByIdempotencyKey(command.sourceSystem(), idempotencyKey);
        if (existing.isPresent()) {
            return answerExisting(command.sourceSystem(), existing.get(), requestHash);
        }

        Instant now = clock.instant();
        if (command.expiresAt() != null && !command.expiresAt().isAfter(now)) {
            rejectInvalid(authenticatedSourceSystem,
                    List.of(new FieldError("expiresAt", "is in the past; the notification has already expired")));
        }

        IntakeContext ctx = new IntakeContext(command, idempotencyKey, requestHash, now);
        try {
            tx.executeWithoutResult(status -> {
                for (IntakeStep step : steps) {
                    step.apply(ctx);
                    if (ctx.existing() != null) {
                        return;
                    }
                }
            });
        } catch (NoEligibleChannelException e) {
            reject(command.sourceSystem(), HttpStatus.UNPROCESSABLE_ENTITY, NO_ELIGIBLE_CHANNEL,
                    "No recipient has an eligible channel", List.of(), AuditDetails.create());
        }
        if (ctx.existing() != null) {
            // Lost a race with a concurrent submission using the same key.
            return answerExisting(command.sourceSystem(), ctx.existing(), requestHash);
        }
        MDC.put("notificationId", ctx.notificationId().toString());
        meters.counter(ACCEPTED_METRIC).increment();
        return new SubmissionResult(ctx.notificationId(), ctx.status(), false);
    }

    private SubmissionResult answerExisting(String sourceSystem, StoredNotification existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            reject(sourceSystem, HttpStatus.CONFLICT, IDEMPOTENCY_KEY_CONFLICT,
                    "Idempotency-Key was already used with a different request body", List.of(), AuditDetails.create());
        }
        MDC.put("notificationId", existing.id().toString());
        tx.executeWithoutResult(status -> audit.record(existing.id(), null, sourceSystem,
                AuditEventType.DUPLICATE_SUBMISSION, null, AuditDetails.none()));
        return new SubmissionResult(existing.id(), existing.status(), true);
    }

    private ObjectNode parse(String sourceSystem, String rawBody) {
        try {
            JsonNode node = rawBody == null || rawBody.isBlank() ? null : objectMapper.readTree(rawBody);
            if (node instanceof ObjectNode object) {
                return object;
            }
        } catch (JsonProcessingException e) {
            // fall through
        }
        return reject(sourceSystem, HttpStatus.BAD_REQUEST, MALFORMED_REQUEST,
                "Request body must be a JSON object", List.of(), AuditDetails.create());
    }

    private void rejectInvalid(String sourceSystem, List<FieldError> errors) {
        reject(sourceSystem, HttpStatus.BAD_REQUEST, VALIDATION_FAILED, "Request validation failed", errors,
                AuditDetails.create().invalidFields(errors.stream().map(FieldError::field).distinct().toList()));
    }

    private <T> T reject(String sourceSystem, HttpStatus status, String reasonCode, String detail,
            List<FieldError> errors, AuditDetails details) {
        audit.recordRejection(sourceSystem, reasonCode, details.build());
        meters.counter(REJECTED_METRIC, "reason", reasonCode).increment();
        throw new SubmissionRejectedException(status, reasonCode, detail, errors);
    }
}

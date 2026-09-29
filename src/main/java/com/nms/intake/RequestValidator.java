package com.nms.intake;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nms.common.domain.Channel;
import com.nms.common.domain.NotificationType;
import com.nms.common.domain.Priority;
import com.nms.common.domain.Severity;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Shape validation of a submission: every field rule, the recipient limit, the {@code Idempotency-Key} header, and
 * {@code expiresAt > scheduledAt}. Checks that depend on the current time run later, after the idempotency lookup.
 */
public class RequestValidator {

    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private final Validator validator;
    private final ObjectMapper objectMapper;
    private final int maxRecipients;

    public RequestValidator(Validator validator, ObjectMapper objectMapper, int maxRecipients) {
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.maxRecipients = maxRecipients;
    }

    /** Returns the command, or the list of every invalid field (never both). */
    public Result validate(ObjectNode body, String idempotencyKey) {
        List<FieldError> errors = new ArrayList<>();

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            errors.add(new FieldError("Idempotency-Key", "header is required"));
        } else if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            errors.add(new FieldError("Idempotency-Key", "must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters"));
        }

        ObjectNode known = body.deepCopy();
        for (Iterator<String> names = body.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!NotificationRequest.FIELDS.contains(name)) {
                errors.add(new FieldError(name, "unknown field"));
                known.remove(name);
            }
        }

        NotificationRequest request;
        try {
            request = objectMapper.treeToValue(known, NotificationRequest.class);
        } catch (MismatchedInputException e) {
            String field = e.getPath().isEmpty() ? "body" : Optional.ofNullable(e.getPath().get(0).getFieldName()).orElse("body");
            errors.add(new FieldError(field, "has an invalid type"));
            return Result.invalid(errors);
        } catch (IOException e) {
            errors.add(new FieldError("body", "is not a valid notification request"));
            return Result.invalid(errors);
        }

        validator.validate(request).stream()
                .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .forEach(v -> errors.add(new FieldError(fieldName(v), v.getMessage())));

        if (request.recipients() != null && request.recipients().size() > maxRecipients) {
            errors.add(new FieldError("recipients", "must contain at most " + maxRecipients + " recipients"));
        }

        Instant scheduledAt = parseInstant(request.scheduledAt());
        Instant expiresAt = parseInstant(request.expiresAt());
        if (scheduledAt != null && expiresAt != null && !expiresAt.isAfter(scheduledAt)) {
            errors.add(new FieldError("expiresAt", "must be after scheduledAt"));
        }

        if (!errors.isEmpty()) {
            return Result.invalid(errors);
        }
        return Result.valid(new SubmitCommand(
                request.sourceSystem(),
                request.eventId(),
                NotificationType.valueOf(request.type()),
                Severity.valueOf(request.severity()),
                Priority.valueOf(request.priority()),
                List.copyOf(request.recipients()),
                request.channels() == null ? List.of() : request.channels().stream().map(Channel::valueOf).toList(),
                request.subject(),
                request.body(),
                scheduledAt,
                expiresAt));
    }

    private static String fieldName(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int cut = path.indexOf('[');
        if (cut < 0) {
            cut = path.indexOf('.');
        }
        return cut < 0 ? path : path.substring(0, cut);
    }

    private static Instant parseInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public record Result(SubmitCommand command, List<FieldError> errors) {

        static Result valid(SubmitCommand command) {
            return new Result(command, List.of());
        }

        static Result invalid(List<FieldError> errors) {
            return new Result(null, List.copyOf(errors));
        }

        public boolean isValid() {
            return command != null;
        }
    }
}

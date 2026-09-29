package com.nms.intake.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The string must be an ISO-8601 timestamp with an offset, e.g. {@code 2026-10-01T10:00:00Z}. Null is valid. */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = IsoTimestampValidator.class)
public @interface IsoTimestamp {

    String message() default "must be an ISO-8601 timestamp with offset, e.g. 2026-10-01T10:00:00Z";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

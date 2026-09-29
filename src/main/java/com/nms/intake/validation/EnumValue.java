package com.nms.intake.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The string must be one of the enum's constant names (exact case). Null is valid; combine with {@code @NotNull}. */
@Target({ElementType.FIELD, ElementType.TYPE_USE, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = EnumValueValidator.class)
public @interface EnumValue {

    Class<? extends Enum<?>> value();

    String message() default "must be one of {allowed}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

package com.nms.intake.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Arrays;
import java.util.List;
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;

public class EnumValueValidator implements ConstraintValidator<EnumValue, String> {

    private List<String> allowed;

    @Override
    public void initialize(EnumValue annotation) {
        allowed = Arrays.stream(annotation.value().getEnumConstants()).map(Enum::name).toList();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || allowed.contains(value)) {
            return true;
        }
        context.unwrap(HibernateConstraintValidatorContext.class).addMessageParameter("allowed", allowed.toString());
        return false;
    }
}

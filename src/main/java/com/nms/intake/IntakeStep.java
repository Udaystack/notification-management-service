package com.nms.intake;

/**
 * One ordered step of the intake transaction. Steps are Spring beans ordered with {@code @Order}; a new step can be
 * added without changing the others.
 */
public interface IntakeStep {

    void apply(IntakeContext context);
}

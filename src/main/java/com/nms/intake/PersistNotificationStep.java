package com.nms.intake;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Inserts the notification; if the idempotency key is already taken, records the existing one and stops. */
@Component
@Order(10)
class PersistNotificationStep implements IntakeStep {

    private final NotificationRepository notifications;

    PersistNotificationStep(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Override
    public void apply(IntakeContext ctx) {
        boolean inserted = notifications.insertIfAbsent(
                ctx.notificationId, ctx.command, ctx.idempotencyKey, ctx.requestHash, ctx.now);
        if (!inserted) {
            ctx.existing = notifications
                    .findByIdempotencyKey(ctx.command.sourceSystem(), ctx.idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Conflicting notification vanished"));
        }
    }
}

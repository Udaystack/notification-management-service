package com.nms.intake;

import com.nms.common.AddressMasker;
import com.nms.common.domain.Channel;
import com.nms.dedup.OriginalDelivery;
import com.nms.delivery.DeliveryRepository;
import com.nms.delivery.NewDelivery;
import com.nms.delivery.NotificationStatusUpdater;
import com.nms.intake.IntakeContext.CreatedDelivery;
import com.nms.intake.IntakeContext.RecipientChannel;
import com.nms.routing.RoutingDecision;
import java.time.Instant;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Creates one delivery per selected (recipient, channel): {@code SUPPRESSED} when it duplicates an earlier delivery
 * of the same event, otherwise {@code PENDING}, due at {@code scheduledAt} or now. Fails the whole intake if no
 * recipient got any channel, then recomputes the notification's overall status.
 */
@Component
@Order(30)
class CreateDeliveriesStep implements IntakeStep {

    private final DeliveryRepository deliveries;
    private final NotificationStatusUpdater statusUpdater;

    CreateDeliveriesStep(DeliveryRepository deliveries, NotificationStatusUpdater statusUpdater) {
        this.deliveries = deliveries;
        this.statusUpdater = statusUpdater;
    }

    @Override
    public void apply(IntakeContext ctx) {
        if (ctx.decisions.stream().noneMatch(RoutingDecision::hasDeliveries)) {
            throw new NoEligibleChannelException();
        }
        Instant scheduledAt = ctx.command.scheduledAt();
        Instant due = scheduledAt != null && scheduledAt.isAfter(ctx.now) ? scheduledAt : ctx.now;
        for (RoutingDecision decision : ctx.decisions) {
            for (Channel channel : decision.selected()) {
                String address = ctx.preferences.get(decision.recipientId()).channel(channel).orElseThrow().address();
                NewDelivery delivery = new NewDelivery(UUID.randomUUID(), ctx.notificationId, decision.recipientId(),
                        channel, AddressMasker.mask(channel, address), due);
                OriginalDelivery original = ctx.duplicates.get(new RecipientChannel(decision.recipientId(), channel));
                if (original != null) {
                    deliveries.insertSuppressed(delivery, original.deliveryId(), ctx.now);
                } else {
                    deliveries.insertPending(delivery, ctx.now);
                    ctx.deliveries.add(delivery);
                }
                ctx.created.add(new CreatedDelivery(delivery, original));
            }
        }
        ctx.status = statusUpdater.recompute(ctx.notificationId, ctx.now);
    }
}

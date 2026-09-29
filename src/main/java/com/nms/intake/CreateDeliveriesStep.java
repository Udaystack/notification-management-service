package com.nms.intake;

import com.nms.common.AddressMasker;
import com.nms.common.domain.Channel;
import com.nms.delivery.DeliveryRepository;
import com.nms.delivery.NewDelivery;
import com.nms.routing.RoutingDecision;
import java.time.Instant;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Creates one {@code PENDING} delivery per selected (recipient, channel), due at {@code scheduledAt} or now.
 * Fails the whole intake if no recipient got any channel.
 */
@Component
@Order(30)
class CreateDeliveriesStep implements IntakeStep {

    private final DeliveryRepository deliveries;

    CreateDeliveriesStep(DeliveryRepository deliveries) {
        this.deliveries = deliveries;
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
                deliveries.insertPending(delivery, ctx.now);
                ctx.deliveries.add(delivery);
            }
        }
    }
}

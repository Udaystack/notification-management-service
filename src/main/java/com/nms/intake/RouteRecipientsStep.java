package com.nms.intake;

import com.nms.recipient.RecipientPreferenceRepository;
import com.nms.routing.RoutingPolicy;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Reads recipient preferences and routes every recipient. */
@Component
@Order(20)
class RouteRecipientsStep implements IntakeStep {

    private final RecipientPreferenceRepository preferences;
    private final RoutingPolicy routingPolicy;

    RouteRecipientsStep(RecipientPreferenceRepository preferences, RoutingPolicy routingPolicy) {
        this.preferences = preferences;
        this.routingPolicy = routingPolicy;
    }

    @Override
    public void apply(IntakeContext ctx) {
        ctx.preferences = preferences.findAll(ctx.command.recipients());
        for (String recipientId : ctx.command.recipients()) {
            ctx.decisions.add(routingPolicy.route(
                    recipientId,
                    ctx.command.channels(),
                    ctx.command.type(),
                    ctx.command.severity(),
                    ctx.preferences.get(recipientId)));
        }
    }
}

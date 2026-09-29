package com.nms.intake;

import com.nms.audit.AuditDetails;
import com.nms.audit.AuditService;
import com.nms.common.domain.AuditEventType;
import com.nms.common.domain.Channel;
import com.nms.delivery.NewDelivery;
import com.nms.routing.RoutingDecision;
import com.nms.routing.RoutingReason;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Audits acceptance, one routing decision per recipient, and one queue event per delivery. */
@Component
@Order(40)
class AuditIntakeStep implements IntakeStep {

    private final AuditService audit;

    AuditIntakeStep(AuditService audit) {
        this.audit = audit;
    }

    @Override
    public void apply(IntakeContext ctx) {
        String sourceSystem = ctx.command.sourceSystem();
        audit.record(ctx.notificationId, null, sourceSystem, AuditEventType.NOTIFICATION_ACCEPTED, null,
                AuditDetails.create().channelCount(ctx.deliveries.size()).build());
        for (RoutingDecision decision : ctx.decisions) {
            audit.record(ctx.notificationId, null, sourceSystem, AuditEventType.ROUTING_DECIDED,
                    decision.outcome() == null ? null : decision.outcome().name(),
                    AuditDetails.create()
                            .recipientId(decision.recipientId())
                            .routing(decision.selected(), names(decision.added()), names(decision.removed()))
                            .build());
        }
        for (NewDelivery delivery : ctx.deliveries) {
            audit.record(ctx.notificationId, delivery.id(), sourceSystem, AuditEventType.DELIVERY_QUEUED, null,
                    AuditDetails.create()
                            .recipientId(delivery.recipientId())
                            .channel(delivery.channel())
                            .address(delivery.channel(), ctx.preferences.get(delivery.recipientId())
                                    .channel(delivery.channel()).orElseThrow().address())
                            .nextAttemptAt(delivery.nextAttemptAt())
                            .build());
        }
    }

    private static Map<Channel, String> names(Map<Channel, RoutingReason> reasons) {
        Map<Channel, String> result = new LinkedHashMap<>();
        reasons.forEach((channel, reason) -> result.put(channel, reason.name()));
        return result;
    }
}

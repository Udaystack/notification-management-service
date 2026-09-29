package com.nms.routing;

import com.nms.common.domain.Channel;
import java.util.List;
import java.util.Map;

/**
 * Routing outcome for one recipient.
 *
 * @param selected channels that get a delivery, in rule order
 * @param added reason each selected channel was added
 * @param removed reason each candidate channel was removed
 * @param outcome {@code NO_ELIGIBLE_CHANNEL} or {@code UNKNOWN_RECIPIENT} when nothing was selected, else null
 */
public record RoutingDecision(
        String recipientId,
        List<Channel> selected,
        Map<Channel, RoutingReason> added,
        Map<Channel, RoutingReason> removed,
        RoutingReason outcome) {

    public boolean hasDeliveries() {
        return !selected.isEmpty();
    }
}

package com.nms.delivery;

import static com.nms.common.domain.DeliveryStatus.EXPIRED;
import static com.nms.common.domain.DeliveryStatus.FAILED;
import static com.nms.common.domain.DeliveryStatus.IN_FLIGHT;
import static com.nms.common.domain.DeliveryStatus.PENDING;
import static com.nms.common.domain.DeliveryStatus.RETRY_SCHEDULED;
import static com.nms.common.domain.DeliveryStatus.SENT;
import static com.nms.common.domain.DeliveryStatus.SUPPRESSED;

import com.nms.common.domain.DeliveryStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The only allowed delivery state transitions (see the notification-status spec). */
public final class DeliveryStateMachine {

    private static final Map<DeliveryStatus, Set<DeliveryStatus>> ALLOWED = new EnumMap<>(DeliveryStatus.class);

    static {
        ALLOWED.put(PENDING, EnumSet.of(IN_FLIGHT, EXPIRED));
        // IN_FLIGHT -> IN_FLIGHT is a reclaim after the lease expired.
        ALLOWED.put(IN_FLIGHT, EnumSet.of(SENT, RETRY_SCHEDULED, FAILED, EXPIRED, IN_FLIGHT));
        ALLOWED.put(RETRY_SCHEDULED, EnumSet.of(IN_FLIGHT, EXPIRED));
        ALLOWED.put(SENT, EnumSet.noneOf(DeliveryStatus.class));
        ALLOWED.put(FAILED, EnumSet.noneOf(DeliveryStatus.class));
        ALLOWED.put(EXPIRED, EnumSet.noneOf(DeliveryStatus.class));
        // Assigned only when a delivery is created; nothing leads into or out of it.
        ALLOWED.put(SUPPRESSED, EnumSet.noneOf(DeliveryStatus.class));
    }

    private DeliveryStateMachine() {
    }

    public static boolean canTransition(DeliveryStatus from, DeliveryStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    /** Returns {@code to} if allowed, otherwise throws without any side effect. */
    public static DeliveryStatus transition(DeliveryStatus from, DeliveryStatus to) {
        if (!canTransition(from, to)) {
            throw new InvalidTransitionException(from, to);
        }
        return to;
    }

    public static final class InvalidTransitionException extends IllegalStateException {

        public InvalidTransitionException(DeliveryStatus from, DeliveryStatus to) {
            super("Invalid delivery transition " + from + " -> " + to);
        }
    }
}

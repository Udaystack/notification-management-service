package com.nms.routing;

/** Why a channel was added to or removed from a recipient's routing, or why a recipient got no delivery. */
public enum RoutingReason {
    REQUESTED,
    TYPE_DEFAULT,
    SEVERITY_ESCALATION,
    FALLBACK,
    RECIPIENT_OPT_OUT,
    NO_ADDRESS,
    NO_ELIGIBLE_CHANNEL,
    UNKNOWN_RECIPIENT
}

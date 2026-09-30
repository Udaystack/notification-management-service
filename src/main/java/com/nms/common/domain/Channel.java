package com.nms.common.domain;

public enum Channel {
    EMAIL,
    SMS,
    PUSH,
    /** HTTP POST to the recipient's webhook URL; only when requested (see the channel-routing spec). */
    WEBHOOK
}

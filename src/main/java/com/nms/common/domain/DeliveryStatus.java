package com.nms.common.domain;

public enum DeliveryStatus {
    PENDING(false),
    IN_FLIGHT(false),
    SENT(true),
    RETRY_SCHEDULED(false),
    FAILED(true),
    /** Duplicate of an earlier delivery of the same event; assigned only at creation and never sent. */
    SUPPRESSED(true),
    EXPIRED(true);

    private final boolean terminal;

    DeliveryStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}

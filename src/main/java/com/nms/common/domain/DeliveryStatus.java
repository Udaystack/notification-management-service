package com.nms.common.domain;

public enum DeliveryStatus {
    PENDING(false),
    IN_FLIGHT(false),
    SENT(true),
    RETRY_SCHEDULED(false),
    FAILED(true),
    EXPIRED(true);

    private final boolean terminal;

    DeliveryStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}

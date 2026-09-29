package com.nms.common.domain;

/** Processing order for deliveries; a higher rank is claimed first. */
public enum Priority {
    LOW(0),
    NORMAL(1),
    HIGH(2);

    private final int rank;

    Priority(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}

package com.nms.common.domain;

public enum FailureClass {
    TRANSIENT(true),
    TIMEOUT(true),
    RATE_LIMITED(true),
    PERMANENT_REJECTION(false),
    INVALID_RECIPIENT(false),
    AUTH_ERROR(false);

    private final boolean retryable;

    FailureClass(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}

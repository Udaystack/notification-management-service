package com.nms.security;

/** Where the authenticated source system is stored for the current request. */
public final class SourceSystemContext {

    /** Request attribute holding the authenticated source system; read with {@code @RequestAttribute}. */
    public static final String ATTRIBUTE = "nms.sourceSystem";

    private SourceSystemContext() {
    }
}

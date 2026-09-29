package com.nms.api;

/** Unknown ID, malformed ID, or owned by another source system; all look the same to the caller. */
class NotificationNotFoundException extends RuntimeException {

    NotificationNotFoundException() {
        super("Notification not found");
    }
}

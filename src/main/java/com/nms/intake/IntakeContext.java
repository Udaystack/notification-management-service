package com.nms.intake;

import com.nms.common.domain.Channel;
import com.nms.common.domain.NotificationStatus;
import com.nms.dedup.OriginalDelivery;
import com.nms.delivery.NewDelivery;
import com.nms.recipient.RecipientPreferences;
import com.nms.routing.RoutingDecision;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** State shared by the intake steps of one submission, inside one transaction. */
public final class IntakeContext {

    final SubmitCommand command;
    final String idempotencyKey;
    final String requestHash;
    final Instant now;
    final UUID notificationId = UUID.randomUUID();

    Map<String, RecipientPreferences> preferences = Map.of();
    final List<RoutingDecision> decisions = new ArrayList<>();
    final List<NewDelivery> deliveries = new ArrayList<>();

    /** Selected (recipient, channel) pairs that duplicate an earlier delivery of the same event. */
    final Map<RecipientChannel, OriginalDelivery> duplicates = new HashMap<>();

    /** Every delivery created, pending or suppressed, in creation order. */
    final List<CreatedDelivery> created = new ArrayList<>();

    /** Overall status after the deliveries were created. */
    NotificationStatus status = NotificationStatus.ACCEPTED;

    /** Set when the key already exists; stops the remaining steps. */
    StoredNotification existing;

    public IntakeContext(SubmitCommand command, String idempotencyKey, String requestHash, Instant now) {
        this.command = command;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.now = now;
    }

    public UUID notificationId() {
        return notificationId;
    }

    public StoredNotification existing() {
        return existing;
    }

    public List<NewDelivery> deliveries() {
        return deliveries;
    }

    public NotificationStatus status() {
        return status;
    }

    record RecipientChannel(String recipientId, Channel channel) {}

    /** @param suppressedBy the original delivery when this one was created {@code SUPPRESSED}, else null */
    record CreatedDelivery(NewDelivery delivery, OriginalDelivery suppressedBy) {}
}

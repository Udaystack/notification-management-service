package com.nms.intake;

import com.nms.delivery.NewDelivery;
import com.nms.recipient.RecipientPreferences;
import com.nms.routing.RoutingDecision;
import java.time.Instant;
import java.util.ArrayList;
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
}

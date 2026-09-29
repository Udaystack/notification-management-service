package com.nms.audit;

import com.nms.common.AddressMasker;
import com.nms.common.domain.Channel;
import com.nms.common.domain.FailureClass;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builder for the {@code details} of an audit event. It only offers non-sensitive fields: there is no way to
 * add a subject, body, API key, or unmasked address. Addresses are masked on the way in.
 */
public final class AuditDetails {

    private final Map<String, Object> values = new LinkedHashMap<>();

    private AuditDetails() {
    }

    public static AuditDetails create() {
        return new AuditDetails();
    }

    public static Map<String, Object> none() {
        return Map.of();
    }

    public AuditDetails recipientId(String recipientId) {
        values.put("recipientId", recipientId);
        return this;
    }

    public AuditDetails channel(Channel channel) {
        values.put("channel", channel.name());
        return this;
    }

    /** Stores the address masked; the raw value is never kept. */
    public AuditDetails address(Channel channel, String rawAddress) {
        values.put("address", AddressMasker.mask(channel, rawAddress));
        return this;
    }

    /** The deliverable delivery that a suppressed delivery duplicates. */
    public AuditDetails original(UUID notificationId, UUID deliveryId) {
        values.put("originalDeliveryId", deliveryId.toString());
        values.put("originalNotificationId", notificationId.toString());
        return this;
    }

    public AuditDetails failureClass(FailureClass failureClass) {
        values.put("failureClass", failureClass.name());
        return this;
    }

    public AuditDetails attempt(int attempt) {
        values.put("attempt", attempt);
        return this;
    }

    public AuditDetails nextAttemptAt(Instant nextAttemptAt) {
        values.put("nextAttemptAt", nextAttemptAt.toString());
        return this;
    }

    public AuditDetails retryAfter(Duration retryAfter) {
        values.put("retryAfterSeconds", retryAfter.toSeconds());
        return this;
    }

    /** Names of rejected fields only, never their values. */
    public AuditDetails invalidFields(List<String> fieldNames) {
        values.put("invalidFields", List.copyOf(fieldNames));
        return this;
    }

    public AuditDetails declaredSourceSystem(String declared) {
        values.put("declaredSourceSystem", declared);
        return this;
    }

    public AuditDetails channelCount(int count) {
        values.put("deliveryCount", count);
        return this;
    }

    /**
     * Routing decision for one recipient: selected channels and, per channel, the rule that added or removed it.
     */
    public AuditDetails routing(List<Channel> selected, Map<Channel, String> added, Map<Channel, String> removed) {
        values.put("selectedChannels", selected.stream().map(Channel::name).toList());
        values.put("added", toNames(added));
        values.put("removed", toNames(removed));
        return this;
    }

    public Map<String, Object> build() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static Map<String, String> toNames(Map<Channel, String> reasons) {
        Map<String, String> result = new LinkedHashMap<>();
        reasons.forEach((channel, reason) -> result.put(channel.name(), reason));
        return result;
    }
}

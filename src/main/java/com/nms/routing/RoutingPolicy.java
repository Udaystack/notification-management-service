package com.nms.routing;

import com.nms.common.domain.Channel;
import com.nms.common.domain.NotificationType;
import com.nms.common.domain.Severity;
import com.nms.recipient.ChannelPreference;
import com.nms.recipient.RecipientPreferences;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Ordered routing rules (see the channel-routing spec): requested or type-default channels, severity escalation,
 * disabled-channel removal, opt-out removal, missing-address removal, then fallback if nothing is left.
 */
public final class RoutingPolicy {

    private final Map<NotificationType, List<Channel>> defaultChannels;
    private final Map<Severity, List<Channel>> severityEscalation;
    private final Channel fallbackChannel;
    private final Set<Channel> disabledChannels;

    public RoutingPolicy(
            Map<NotificationType, List<Channel>> defaultChannels,
            Map<Severity, List<Channel>> severityEscalation,
            Channel fallbackChannel) {
        this(defaultChannels, severityEscalation, fallbackChannel, Set.of());
    }

    /** @param disabledChannels channels removed with {@code CHANNEL_DISABLED} before any other removal rule */
    public RoutingPolicy(
            Map<NotificationType, List<Channel>> defaultChannels,
            Map<Severity, List<Channel>> severityEscalation,
            Channel fallbackChannel,
            Set<Channel> disabledChannels) {
        this.defaultChannels = Map.copyOf(defaultChannels);
        this.severityEscalation = Map.copyOf(severityEscalation);
        this.fallbackChannel = fallbackChannel;
        this.disabledChannels = Set.copyOf(disabledChannels);
    }

    /**
     * @param preferences the recipient's stored preferences, or {@code null} if the recipient is unknown
     */
    public RoutingDecision route(
            String recipientId,
            List<Channel> requested,
            NotificationType type,
            Severity severity,
            RecipientPreferences preferences) {
        if (preferences == null) {
            return new RoutingDecision(recipientId, List.of(), Map.of(), Map.of(), RoutingReason.UNKNOWN_RECIPIENT);
        }

        Map<Channel, RoutingReason> candidates = new LinkedHashMap<>();
        if (!requested.isEmpty()) {
            requested.forEach(c -> candidates.putIfAbsent(c, RoutingReason.REQUESTED));
        } else {
            defaultChannels.getOrDefault(type, List.of())
                    .forEach(c -> candidates.putIfAbsent(c, RoutingReason.TYPE_DEFAULT));
        }
        severityEscalation.getOrDefault(severity, List.of())
                .forEach(c -> candidates.putIfAbsent(c, RoutingReason.SEVERITY_ESCALATION));

        Map<Channel, RoutingReason> removed = new EnumMap<>(Channel.class);
        for (Channel channel : List.copyOf(candidates.keySet())) {
            if (disabledChannels.contains(channel)) {
                candidates.remove(channel);
                removed.put(channel, RoutingReason.CHANNEL_DISABLED);
            }
        }
        for (Channel channel : List.copyOf(candidates.keySet())) {
            Optional<ChannelPreference> pref = preferences.channel(channel);
            if (pref.isPresent() && pref.get().optedOut()) {
                candidates.remove(channel);
                removed.put(channel, RoutingReason.RECIPIENT_OPT_OUT);
            }
        }
        for (Channel channel : List.copyOf(candidates.keySet())) {
            if (preferences.channel(channel).isEmpty()) {
                candidates.remove(channel);
                removed.put(channel, RoutingReason.NO_ADDRESS);
            }
        }

        if (candidates.isEmpty()) {
            Optional<ChannelPreference> fallback = preferences.channel(fallbackChannel);
            if (fallback.isPresent() && !fallback.get().optedOut()) {
                candidates.put(fallbackChannel, RoutingReason.FALLBACK);
            }
        }

        RoutingReason outcome = candidates.isEmpty() ? RoutingReason.NO_ELIGIBLE_CHANNEL : null;
        return new RoutingDecision(
                recipientId, new ArrayList<>(candidates.keySet()), candidates, removed, outcome);
    }
}

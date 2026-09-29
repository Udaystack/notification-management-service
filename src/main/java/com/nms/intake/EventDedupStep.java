package com.nms.intake;

import com.nms.common.config.NmsProperties;
import com.nms.common.domain.Channel;
import com.nms.dedup.EventDedupRepository;
import com.nms.intake.IntakeContext.RecipientChannel;
import com.nms.routing.RoutingDecision;
import java.time.Instant;
import java.util.List;
import java.util.TreeMap;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Event-level deduplication (when {@code nms.dedup.enabled}): for every routed (recipient, channel), finds an
 * earlier deliverable delivery of the same event within the window. Concurrent submissions of the same event are
 * serialized with transaction-scoped advisory locks, taken in sorted key order to avoid deadlocks.
 */
@Component
@Order(25)
class EventDedupStep implements IntakeStep {

    private final EventDedupRepository dedup;
    private final NmsProperties.Dedup config;

    EventDedupStep(EventDedupRepository dedup, NmsProperties properties) {
        this.dedup = dedup;
        this.config = properties.dedup();
    }

    @Override
    public void apply(IntakeContext ctx) {
        if (!config.enabled()) {
            return;
        }
        String sourceSystem = ctx.command.sourceSystem();
        String eventId = ctx.command.eventId();

        TreeMap<String, RecipientChannel> pairsByKey = new TreeMap<>();
        for (RoutingDecision decision : ctx.decisions) {
            for (Channel channel : decision.selected()) {
                pairsByKey.put(key(sourceSystem, eventId, decision.recipientId(), channel),
                        new RecipientChannel(decision.recipientId(), channel));
            }
        }
        pairsByKey.keySet().forEach(dedup::lock);

        Instant since = ctx.now.minus(config.window());
        pairsByKey.values().forEach(pair -> dedup
                .findOriginal(sourceSystem, eventId, pair.recipientId(), pair.channel(), since)
                .ifPresent(original -> ctx.duplicates.put(pair, original)));
    }

    /** Length-prefixed so that no combination of field values can produce the same key. */
    static String key(String sourceSystem, String eventId, String recipientId, Channel channel) {
        return List.of(sourceSystem, eventId, recipientId, channel.name()).stream()
                .map(part -> part.length() + ":" + part)
                .reduce("", String::concat);
    }

}

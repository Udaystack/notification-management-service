package com.nms.channel;

import com.nms.common.domain.Channel;
import com.nms.common.domain.FailureClass;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulated provider with deterministic failure injection: the address containing a marker decides the outcome.
 * Sends are idempotent per delivery ID, like a real provider honoring an idempotency key.
 */
public class SimulatedProvider implements ChannelProvider {

    static final Duration RATE_LIMIT_RETRY_AFTER = Duration.ofSeconds(30);

    private final Channel channel;
    private final Map<UUID, Integer> sends = new ConcurrentHashMap<>();

    public SimulatedProvider(Channel channel) {
        this.channel = channel;
    }

    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public DeliveryResult send(DeliveryRequest request) {
        if (sends.containsKey(request.idempotencyKey())) {
            return new DeliveryResult.Success();
        }
        String address = request.address();
        if (address.contains("+transient")) {
            return new DeliveryResult.Failure(FailureClass.TRANSIENT);
        }
        if (address.contains("+timeout")) {
            return new DeliveryResult.Failure(FailureClass.TIMEOUT);
        }
        if (address.contains("+ratelimit")) {
            return new DeliveryResult.Failure(FailureClass.RATE_LIMITED, RATE_LIMIT_RETRY_AFTER);
        }
        if (address.contains("+reject")) {
            return new DeliveryResult.Failure(FailureClass.PERMANENT_REJECTION);
        }
        if (address.contains("+invalid")) {
            return new DeliveryResult.Failure(FailureClass.INVALID_RECIPIENT);
        }
        if (address.contains("+auth")) {
            return new DeliveryResult.Failure(FailureClass.AUTH_ERROR);
        }
        if (address.contains("+flaky") && request.attempt() == 1) {
            return new DeliveryResult.Failure(FailureClass.TRANSIENT);
        }
        sends.merge(request.idempotencyKey(), 1, Integer::sum);
        return new DeliveryResult.Success();
    }

    /** How many messages were actually sent for this delivery ID (for tests and demos). */
    public int sendCount(UUID idempotencyKey) {
        return sends.getOrDefault(idempotencyKey, 0);
    }
}

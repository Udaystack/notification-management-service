package com.nms.delivery;

import com.nms.channel.ChannelProvider;
import com.nms.channel.ChannelProviders;
import com.nms.channel.DeliveryRequest;
import com.nms.channel.DeliveryResult;
import com.nms.common.config.NmsProperties;
import com.nms.common.domain.FailureClass;
import com.nms.recipient.RecipientPreferenceRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Processes claimed deliveries: address lookup, provider call outside any transaction (bounded by the provider
 * timeout), then the outcome in a second transaction.
 */
@Component
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final DeliveryQueue queue;
    private final RecipientPreferenceRepository recipients;
    private final ChannelProviders providers;
    private final DeliveryOutcomeRecorder outcomes;
    private final Duration providerTimeout;
    private final ExecutorService providerCalls = Executors.newVirtualThreadPerTaskExecutor();

    DeliveryWorker(DeliveryQueue queue, RecipientPreferenceRepository recipients, ChannelProviders providers,
            DeliveryOutcomeRecorder outcomes, NmsProperties properties) {
        this.queue = queue;
        this.recipients = recipients;
        this.providers = providers;
        this.outcomes = outcomes;
        this.providerTimeout = properties.worker().providerTimeout();
    }

    public List<ClaimedDelivery> claim(int limit) {
        return queue.claim(limit);
    }

    /** Claims and processes up to {@code limit} deliveries on the calling thread; returns how many were claimed. */
    public int runOnce(int limit) {
        List<ClaimedDelivery> claimed = claim(limit);
        claimed.forEach(this::process);
        return claimed.size();
    }

    public void process(ClaimedDelivery claim) {
        MDC.put("notificationId", claim.notificationId().toString());
        MDC.put("deliveryId", claim.id().toString());
        try {
            processInContext(claim);
        } finally {
            MDC.remove("notificationId");
            MDC.remove("deliveryId");
        }
    }

    private void processInContext(ClaimedDelivery claim) {
        Optional<String> address = recipients.findAddress(claim.recipientId(), claim.channel());
        if (address.isEmpty()) {
            log.warn("No {} address for delivery {} at send time", claim.channel(), claim.id());
            outcomes.record(claim, new DeliveryResult.Failure(FailureClass.INVALID_RECIPIENT), null);
            return;
        }
        DeliveryRequest request = new DeliveryRequest(claim.id(), claim.channel(), address.get(), claim.subject(),
                claim.body(), claim.attempt());
        outcomes.record(claim, call(providers.forChannel(claim.channel()), request), address.get());
    }

    private DeliveryResult call(ChannelProvider provider, DeliveryRequest request) {
        Future<DeliveryResult> future = providerCalls.submit(() -> provider.send(request));
        try {
            DeliveryResult result = future.get(providerTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return result != null ? result : new DeliveryResult.Failure(FailureClass.TRANSIENT);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Provider call for delivery {} exceeded {}", request.idempotencyKey(), providerTimeout);
            return new DeliveryResult.Failure(FailureClass.TIMEOUT);
        } catch (ExecutionException e) {
            log.warn("Provider call for delivery {} failed unexpectedly: {}", request.idempotencyKey(),
                    e.getCause().getClass().getName());
            return new DeliveryResult.Failure(FailureClass.TRANSIENT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return new DeliveryResult.Failure(FailureClass.TRANSIENT);
        }
    }
}

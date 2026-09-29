package com.nms.delivery;

import com.nms.common.config.NmsProperties;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls for due deliveries and processes them on virtual threads, at most {@code concurrency} at a time. Each poll
 * claims only as many rows as there are free slots, so a claimed row never waits while its lease runs down, and
 * keeps claiming while a backlog exists. On shutdown it stops claiming and waits up to the shutdown timeout for in-flight deliveries.
 */
@Component
@ConditionalOnProperty(name = "nms.worker.enabled", havingValue = "true")
class DeliveryPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DeliveryPoller.class);

    private final DeliveryWorker worker;
    private final int batchSize;
    private final Duration pollInterval;
    private final Duration shutdownTimeout;
    private final Semaphore slots;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    DeliveryPoller(DeliveryWorker worker, NmsProperties properties) {
        NmsProperties.Worker config = properties.worker();
        if (config.leaseDuration().compareTo(config.providerTimeout()) <= 0) {
            throw new IllegalStateException("nms.worker.lease-duration (" + config.leaseDuration()
                    + ") must be greater than nms.worker.provider-timeout (" + config.providerTimeout() + ")");
        }
        this.worker = worker;
        this.batchSize = config.batchSize();
        this.pollInterval = config.pollInterval();
        this.shutdownTimeout = config.shutdownTimeout();
        this.slots = new Semaphore(config.concurrency());
    }

    /**
     * Claims and dispatches work until no more is due. While a backlog exists it waits only for the next free slot
     * (at most one poll interval), never for a fixed interval, so throughput is bounded by concurrency and
     * per-delivery time.
     */
    @Scheduled(fixedDelayString = "${nms.worker.poll-interval}")
    void poll() throws InterruptedException {
        while (running.get()) {
            if (!slots.tryAcquire(pollInterval.toMillis(), TimeUnit.MILLISECONDS)) {
                return; // every slot stayed busy for a whole interval
            }
            int acquired = 1;
            while (acquired < batchSize && slots.tryAcquire()) {
                acquired++;
            }
            if (!claimAndDispatch(acquired)) {
                return; // less than a full batch was due: the backlog is drained
            }
        }
    }

    /**
     * @param acquired slots already acquired; unused ones are released
     * @return {@code true} if every acquired slot got a delivery (more work is likely due)
     */
    private boolean claimAndDispatch(int acquired) {
        List<ClaimedDelivery> claimed;
        try {
            claimed = worker.claim(acquired);
        } catch (RuntimeException e) {
            slots.release(acquired);
            log.error("Claiming deliveries failed", e);
            return false;
        }
        slots.release(acquired - claimed.size());
        for (ClaimedDelivery delivery : claimed) {
            executor.execute(() -> {
                try {
                    worker.process(delivery);
                } catch (RuntimeException e) {
                    log.error("Processing delivery {} failed; it will be reclaimed after its lease expires",
                            delivery.id(), e);
                } finally {
                    slots.release();
                }
            });
        }
        return claimed.size() == acquired;
    }

    @Override
    public void start() {
        running.set(true);
    }

    @Override
    public void stop() {
        running.set(false);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Shutdown timeout {} reached; unfinished deliveries will be reclaimed after their lease",
                        shutdownTimeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}

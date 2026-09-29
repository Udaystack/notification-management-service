package com.nms.delivery;

import com.nms.common.domain.Channel;
import com.nms.common.domain.FailureClass;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Delivery outcome counters, tagged by channel (and failure class where one applies). */
@Component
public class DeliveryMetrics {

    public static final String SENT = "nms.deliveries.sent";
    public static final String FAILED = "nms.deliveries.failed";
    public static final String RETRIED = "nms.deliveries.retried";
    public static final String EXPIRED = "nms.deliveries.expired";
    public static final String SUPPRESSED = "nms.deliveries.suppressed";

    private final MeterRegistry registry;

    DeliveryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void sent(Channel channel) {
        registry.counter(SENT, "channel", channel.name()).increment();
    }

    void failed(Channel channel, FailureClass failureClass) {
        registry.counter(FAILED, "channel", channel.name(), "failureClass", failureClass.name()).increment();
    }

    void retried(Channel channel, FailureClass failureClass) {
        registry.counter(RETRIED, "channel", channel.name(), "failureClass", failureClass.name()).increment();
    }

    void expired(Channel channel) {
        registry.counter(EXPIRED, "channel", channel.name()).increment();
    }

    public void suppressed(Channel channel, String sourceSystem) {
        registry.counter(SUPPRESSED, "channel", channel.name(), "sourceSystem", sourceSystem).increment();
    }
}

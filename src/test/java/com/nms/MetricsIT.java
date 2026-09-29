package com.nms;

import static org.assertj.core.api.Assertions.assertThat;

import com.nms.delivery.DeliveryMetrics;
import com.nms.delivery.DeliveryTestSupport;
import com.nms.intake.NotificationIntakeService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MetricsIT extends DeliveryTestSupport {

    @Autowired
    MeterRegistry meters;

    @Test
    void countersIncrement() throws Exception {
        double accepted = count(NotificationIntakeService.ACCEPTED_METRIC);
        double rejected = count(NotificationIntakeService.REJECTED_METRIC, "reason", "VALIDATION_FAILED");
        double sent = count(DeliveryMetrics.SENT, "channel", "EMAIL");
        double retried = count(DeliveryMetrics.RETRIED, "channel", "EMAIL", "failureClass", "TRANSIENT");
        double failed = count(DeliveryMetrics.FAILED, "channel", "EMAIL", "failureClass", "INVALID_RECIPIENT");

        submitTo("cust-1001");
        submitTo("cust-2001");
        submitTo("cust-2005");
        submit(BILLING_KEY, newKey(), request("billing"));
        worker.runOnce(10);

        assertThat(count(NotificationIntakeService.ACCEPTED_METRIC)).isEqualTo(accepted + 3);
        assertThat(count(NotificationIntakeService.REJECTED_METRIC, "reason", "VALIDATION_FAILED")).isEqualTo(rejected + 1);
        assertThat(count(DeliveryMetrics.SENT, "channel", "EMAIL")).isEqualTo(sent + 1);
        assertThat(count(DeliveryMetrics.RETRIED, "channel", "EMAIL", "failureClass", "TRANSIENT")).isEqualTo(retried + 1);
        assertThat(count(DeliveryMetrics.FAILED, "channel", "EMAIL", "failureClass", "INVALID_RECIPIENT"))
                .isEqualTo(failed + 1);
    }

    private double count(String name, String... tags) {
        Counter counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }
}

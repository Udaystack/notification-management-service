package com.nms.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class AuthErrorIT extends DeliveryTestSupport {

    @Autowired
    MeterRegistry meters;

    @Test
    void authErrorFailsImmediatelyWithErrorLogAndMetric(CapturedOutput output) throws Exception {
        double before = authErrors();
        UUID id = submitTo("cust-2006");
        worker.runOnce(10);

        assertThat(deliveryStatus(id)).isEqualTo("FAILED");
        assertThat(attempts(id)).isEqualTo(1);
        assertThat(output.getOut().lines())
                .anySatisfy(line -> assertThat(line)
                        .contains("\"level\":\"ERROR\"", "Provider rejected NMS credentials", id.toString()));
        assertThat(authErrors()).isEqualTo(before + 1);
    }

    private double authErrors() {
        Counter counter = meters.find(DeliveryMetrics.FAILED).tags("channel", "EMAIL", "failureClass", "AUTH_ERROR")
                .counter();
        return counter == null ? 0 : counter.count();
    }
}

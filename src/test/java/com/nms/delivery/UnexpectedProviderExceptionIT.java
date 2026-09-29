package com.nms.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nms.channel.SimulatedProvider;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class UnexpectedProviderExceptionIT extends DeliveryTestSupport {

    @MockitoSpyBean(name = "pushProvider")
    SimulatedProvider pushProvider;

    @Test
    void unexpectedException() throws Exception {
        doThrow(new IllegalStateException("provider SDK bug")).when(pushProvider).send(any());
        ObjectNode body = request("billing", "cust-1001");
        body.putArray("channels").add("PUSH");
        UUID id = submitAndGetDelivery(body);

        worker.runOnce(10);

        assertThat(deliveryStatus(id)).isEqualTo("RETRY_SCHEDULED");
        assertThat(jdbc.sql("SELECT last_failure_class FROM delivery WHERE id = ?").param(id)
                .query(String.class).single()).isEqualTo("TRANSIENT");
    }
}

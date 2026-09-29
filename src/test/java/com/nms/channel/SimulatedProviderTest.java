package com.nms.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.nms.common.domain.Channel;
import com.nms.common.domain.FailureClass;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SimulatedProviderTest {

    @Test
    void injectedFailure() {
        DeliveryResult result = new SimulatedProvider(Channel.EMAIL).send(
                new DeliveryRequest(UUID.randomUUID(), Channel.EMAIL, "user+invalid@example.com", "s", "b", 1));
        assertThat(result).isEqualTo(new DeliveryResult.Failure(FailureClass.INVALID_RECIPIENT));
    }
}

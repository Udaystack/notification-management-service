package com.nms.delivery;

import static com.nms.common.domain.DeliveryStatus.EXPIRED;
import static com.nms.common.domain.DeliveryStatus.FAILED;
import static com.nms.common.domain.DeliveryStatus.IN_FLIGHT;
import static com.nms.common.domain.DeliveryStatus.PENDING;
import static com.nms.common.domain.DeliveryStatus.RETRY_SCHEDULED;
import static com.nms.common.domain.DeliveryStatus.SENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nms.common.domain.DeliveryStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DeliveryStateMachineTest {

    @ParameterizedTest(name = "{0} -> {1} allowed")
    @CsvSource({
        "PENDING, IN_FLIGHT",
        "PENDING, EXPIRED",
        "IN_FLIGHT, SENT",
        "IN_FLIGHT, RETRY_SCHEDULED",
        "IN_FLIGHT, FAILED",
        "IN_FLIGHT, EXPIRED",
        "IN_FLIGHT, IN_FLIGHT",
        "RETRY_SCHEDULED, IN_FLIGHT",
        "RETRY_SCHEDULED, EXPIRED"
    })
    void allowedTransitions(DeliveryStatus from, DeliveryStatus to) {
        assertThat(DeliveryStateMachine.transition(from, to)).isEqualTo(to);
    }

    @ParameterizedTest(name = "{0} -> {1} rejected")
    @CsvSource({
        "SENT, IN_FLIGHT",
        "SENT, FAILED",
        "FAILED, RETRY_SCHEDULED",
        "EXPIRED, IN_FLIGHT",
        "PENDING, SENT",
        "PENDING, FAILED",
        "RETRY_SCHEDULED, SENT",
        "IN_FLIGHT, PENDING"
    })
    void invalidTransitionRejected(DeliveryStatus from, DeliveryStatus to) {
        assertThat(DeliveryStateMachine.canTransition(from, to)).isFalse();
        assertThatThrownBy(() -> DeliveryStateMachine.transition(from, to))
                .isInstanceOf(DeliveryStateMachine.InvalidTransitionException.class);
    }

    @org.junit.jupiter.api.Test
    void terminalStatesAllowNoTransition() {
        for (DeliveryStatus to : DeliveryStatus.values()) {
            assertThat(DeliveryStateMachine.canTransition(SENT, to)).isFalse();
            assertThat(DeliveryStateMachine.canTransition(FAILED, to)).isFalse();
            assertThat(DeliveryStateMachine.canTransition(EXPIRED, to)).isFalse();
        }
        assertThat(PENDING.isTerminal()).isFalse();
        assertThat(IN_FLIGHT.isTerminal()).isFalse();
        assertThat(RETRY_SCHEDULED.isTerminal()).isFalse();
    }
}

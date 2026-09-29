package com.nms.delivery;

import static com.nms.common.domain.DeliveryStatus.EXPIRED;
import static com.nms.common.domain.DeliveryStatus.FAILED;
import static com.nms.common.domain.DeliveryStatus.IN_FLIGHT;
import static com.nms.common.domain.DeliveryStatus.PENDING;
import static com.nms.common.domain.DeliveryStatus.RETRY_SCHEDULED;
import static com.nms.common.domain.DeliveryStatus.SENT;
import static org.assertj.core.api.Assertions.assertThat;

import com.nms.common.domain.DeliveryStatus;
import com.nms.common.domain.NotificationStatus;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins the delivery state model and status derivation as they were before event deduplication, so any later change
 * to them shows up as a reviewed diff. Inputs never contain {@code SUPPRESSED}.
 */
class StateModelCharacterizationTest {

    private static final Map<DeliveryStatus, Set<DeliveryStatus>> TRANSITIONS = Map.of(
            PENDING, EnumSet.of(IN_FLIGHT, EXPIRED),
            IN_FLIGHT, EnumSet.of(SENT, RETRY_SCHEDULED, FAILED, EXPIRED, IN_FLIGHT),
            RETRY_SCHEDULED, EnumSet.of(IN_FLIGHT, EXPIRED),
            SENT, EnumSet.noneOf(DeliveryStatus.class),
            FAILED, EnumSet.noneOf(DeliveryStatus.class),
            EXPIRED, EnumSet.noneOf(DeliveryStatus.class));

    @Test
    void transitionTableIsUnchanged() {
        for (DeliveryStatus from : TRANSITIONS.keySet()) {
            for (DeliveryStatus to : TRANSITIONS.keySet()) {
                assertThat(DeliveryStateMachine.canTransition(from, to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(TRANSITIONS.get(from).contains(to));
            }
        }
    }

    @Test
    void derivedStatusesAreUnchanged() {
        Map<List<DeliveryStatus>, NotificationStatus> expected = Map.ofEntries(
                Map.entry(List.of(PENDING), NotificationStatus.ACCEPTED),
                Map.entry(List.of(PENDING, PENDING), NotificationStatus.ACCEPTED),
                Map.entry(List.of(IN_FLIGHT), NotificationStatus.IN_PROGRESS),
                Map.entry(List.of(PENDING, SENT), NotificationStatus.IN_PROGRESS),
                Map.entry(List.of(PENDING, EXPIRED), NotificationStatus.IN_PROGRESS),
                Map.entry(List.of(SENT, RETRY_SCHEDULED), NotificationStatus.IN_PROGRESS),
                Map.entry(List.of(SENT), NotificationStatus.COMPLETED),
                Map.entry(List.of(SENT, SENT), NotificationStatus.COMPLETED),
                Map.entry(List.of(SENT, FAILED), NotificationStatus.PARTIALLY_DELIVERED),
                Map.entry(List.of(SENT, EXPIRED), NotificationStatus.PARTIALLY_DELIVERED),
                Map.entry(List.of(FAILED), NotificationStatus.FAILED),
                Map.entry(List.of(FAILED, EXPIRED), NotificationStatus.FAILED),
                Map.entry(List.of(EXPIRED), NotificationStatus.EXPIRED),
                Map.entry(List.of(EXPIRED, EXPIRED), NotificationStatus.EXPIRED));
        expected.forEach((deliveries, status) ->
                assertThat(NotificationStatusDeriver.derive(deliveries)).as("%s", deliveries).isEqualTo(status));
    }
}

package com.nms.delivery;

import static com.nms.common.domain.DeliveryStatus.FAILED;
import static com.nms.common.domain.DeliveryStatus.PENDING;
import static com.nms.common.domain.DeliveryStatus.RETRY_SCHEDULED;
import static com.nms.common.domain.DeliveryStatus.SENT;
import static org.assertj.core.api.Assertions.assertThat;

import com.nms.common.domain.NotificationStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationStatusDeriverTest {

    @Test
    void acceptedWhileEveryDeliveryIsPending() {
        assertThat(NotificationStatusDeriver.derive(List.of(PENDING, PENDING))).isEqualTo(NotificationStatus.ACCEPTED);
    }

    @Test
    void statusOfAnInProgressNotification() {
        assertThat(NotificationStatusDeriver.derive(List.of(SENT, RETRY_SCHEDULED)))
                .isEqualTo(NotificationStatus.IN_PROGRESS);
    }

    @Test
    void derivedPartialDelivery() {
        assertThat(NotificationStatusDeriver.derive(List.of(SENT, FAILED)))
                .isEqualTo(NotificationStatus.PARTIALLY_DELIVERED);
    }
}

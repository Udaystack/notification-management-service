package com.nms.delivery;

import com.nms.common.domain.Channel;
import java.time.Instant;
import java.util.UUID;

public record NewDelivery(
        UUID id, UUID notificationId, String recipientId, Channel channel, String addressMasked, Instant nextAttemptAt) {}

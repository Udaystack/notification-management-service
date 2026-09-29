package com.nms.delivery;

import java.util.List;

/**
 * Port for claiming due deliveries. The PostgreSQL implementation can be replaced by a broker-backed one without
 * touching the worker.
 */
public interface DeliveryQueue {

    /**
     * Claims up to {@code limit} due deliveries (due {@code PENDING}/{@code RETRY_SCHEDULED}, or {@code IN_FLIGHT}
     * with an expired lease), highest priority first. Deliveries whose notification has expired are marked
     * {@code EXPIRED} instead of being returned.
     */
    List<ClaimedDelivery> claim(int limit);
}

package com.nms.channel;

import com.nms.common.domain.Channel;

/** Port to an external provider for one channel. Real adapters replace the simulated ones. */
public interface ChannelProvider {

    Channel channel();

    DeliveryResult send(DeliveryRequest request);
}

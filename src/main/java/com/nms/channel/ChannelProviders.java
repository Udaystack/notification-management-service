package com.nms.channel;

import com.nms.common.domain.Channel;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Looks up the provider for a channel. */
@Component
public class ChannelProviders {

    private final Map<Channel, ChannelProvider> byChannel = new EnumMap<>(Channel.class);

    ChannelProviders(List<ChannelProvider> providers) {
        providers.forEach(p -> byChannel.put(p.channel(), p));
        for (Channel channel : Channel.values()) {
            if (!byChannel.containsKey(channel)) {
                throw new IllegalStateException("No provider for channel " + channel);
            }
        }
    }

    public ChannelProvider forChannel(Channel channel) {
        return byChannel.get(channel);
    }
}

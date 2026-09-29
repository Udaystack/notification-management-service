package com.nms.recipient;

import com.nms.common.domain.Channel;
import java.util.Map;
import java.util.Optional;

/** A recipient's stored channel addresses and opt-outs. A channel absent from the map has no address. */
public record RecipientPreferences(String recipientId, Map<Channel, ChannelPreference> channels) {

    public Optional<ChannelPreference> channel(Channel channel) {
        return Optional.ofNullable(channels.get(channel));
    }
}

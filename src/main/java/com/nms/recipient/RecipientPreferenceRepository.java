package com.nms.recipient;

import com.nms.common.domain.Channel;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RecipientPreferenceRepository {

    private final JdbcClient jdbc;

    RecipientPreferenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Preferences of the given recipients; unknown recipient IDs are absent from the result. */
    public Map<String, RecipientPreferences> findAll(Collection<String> recipientIds) {
        Map<String, RecipientPreferences> result = new HashMap<>();
        if (recipientIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                SELECT r.id, rc.channel, rc.address, rc.opted_out
                FROM recipient r
                LEFT JOIN recipient_channel rc ON rc.recipient_id = r.id
                WHERE r.id IN (:ids)
                """)
                .param("ids", recipientIds)
                .query(rs -> {
                    String id = rs.getString("id");
                    RecipientPreferences prefs = result.computeIfAbsent(
                            id, k -> new RecipientPreferences(k, new EnumMap<>(Channel.class)));
                    String channel = rs.getString("channel");
                    if (channel != null) {
                        prefs.channels().put(Channel.valueOf(channel),
                                new ChannelPreference(rs.getString("address"), rs.getBoolean("opted_out")));
                    }
                });
        return result;
    }

    /** The recipient's current full address for a channel, read at send time. */
    public Optional<String> findAddress(String recipientId, Channel channel) {
        return jdbc.sql("SELECT address FROM recipient_channel WHERE recipient_id = :id AND channel = :channel")
                .param("id", recipientId)
                .param("channel", channel.name())
                .query(String.class)
                .optional();
    }
}

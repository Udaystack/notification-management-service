package com.nms.intake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nms.common.Hashing;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * SHA-256 of the canonical request body: keys sorted, timestamps normalized to UTC ISO-8601, no whitespace. Field
 * order, formatting, and time-zone notation don't change the hash; list order does.
 */
public final class RequestHasher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RequestHasher() {
    }

    public static String hash(SubmitCommand command) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("sourceSystem", command.sourceSystem());
        canonical.put("eventId", command.eventId());
        canonical.put("type", command.type().name());
        canonical.put("severity", command.severity().name());
        canonical.put("priority", command.priority().name());
        canonical.put("recipients", command.recipients());
        canonical.put("channels", command.channels().stream().map(Enum::name).toList());
        canonical.put("subject", command.subject());
        canonical.put("body", command.body());
        canonical.put("scheduledAt", format(command.scheduledAt()));
        canonical.put("expiresAt", format(command.expiresAt()));
        try {
            return Hashing.sha256Hex(MAPPER.writeValueAsString(canonical));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String format(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}

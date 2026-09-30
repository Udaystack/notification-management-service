package com.nms.channel.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nms.channel.ChannelProvider;
import com.nms.channel.DeliveryRequest;
import com.nms.channel.DeliveryResult;
import com.nms.common.config.NmsProperties;
import com.nms.common.domain.Channel;
import com.nms.common.domain.FailureClass;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Sends {@code WEBHOOK} deliveries as a signed JSON {@code POST} (see the webhook-delivery spec). Redirects are never
 * followed and response bodies are never read. Always registered, so queued webhook deliveries are settled even
 * while the channel is disabled: then no request is sent and the delivery fails with reason {@code CHANNEL_DISABLED}.
 */
@Component
public class WebhookProvider implements ChannelProvider {

    public static final String CHANNEL_DISABLED = "CHANNEL_DISABLED";
    static final String TIMESTAMP_HEADER = "X-NMS-Timestamp";
    static final String SIGNATURE_HEADER = "X-NMS-Signature";

    private final NmsProperties.Webhook config;
    private final WebhookTargetPolicy policy;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final RestClient http;

    @Autowired
    WebhookProvider(NmsProperties properties, ObjectMapper mapper, Clock clock) {
        this(properties, mapper, clock, InetAddress::getAllByName);
    }

    WebhookProvider(NmsProperties properties, ObjectMapper mapper, Clock clock,
            WebhookTargetPolicy.AddressResolver resolver) {
        this.config = properties.webhook();
        this.policy = new WebhookTargetPolicy(config.allowPrivateHosts(), resolver);
        this.mapper = mapper;
        this.clock = clock;
        Duration timeout = properties.worker().providerTimeout();
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Channel channel() {
        return Channel.WEBHOOK;
    }

    @Override
    public DeliveryResult send(DeliveryRequest request) {
        if (!config.enabled()) {
            return new DeliveryResult.Failure(FailureClass.PERMANENT_REJECTION, null, CHANNEL_DISABLED);
        }
        WebhookTargetPolicy.Decision target = policy.check(request.address());
        if (!target.allowed()) {
            return new DeliveryResult.Failure(target.failure());
        }
        Instant now = clock.instant();
        byte[] body = payload(request, now);
        String timestamp = Long.toString(now.getEpochSecond());
        try {
            return http.post()
                    .uri(target.target())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", request.idempotencyKey().toString())
                    .header(TIMESTAMP_HEADER, timestamp)
                    .header(SIGNATURE_HEADER, sign(config.signingSecret(), timestamp, body))
                    .body(body)
                    .exchange((req, response) -> classify(response.getStatusCode().value(),
                            response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), now));
        } catch (ResourceAccessException e) {
            return new DeliveryResult.Failure(
                    e.getCause() instanceof HttpTimeoutException ? FailureClass.TIMEOUT : FailureClass.TRANSIENT);
        }
    }

    private byte[] payload(DeliveryRequest request, Instant now) {
        DeliveryRequest.NotificationInfo n = request.notification();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notificationId", n.notificationId());
        payload.put("deliveryId", request.idempotencyKey());
        payload.put("eventId", n.eventId());
        payload.put("sourceSystem", n.sourceSystem());
        payload.put("type", n.type());
        payload.put("severity", n.severity());
        payload.put("priority", n.priority());
        payload.put("recipientId", n.recipientId());
        payload.put("subject", request.subject());
        payload.put("body", request.body());
        payload.put("attempt", request.attempt());
        payload.put("sentAt", now.toString());
        try {
            return mapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize webhook payload", e);
        }
    }

    /** {@code sha256=} + lowercase hex HMAC-SHA256 of {@code <timestamp>.<body>}. */
    static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    static DeliveryResult classify(int status, String retryAfter, Instant now) {
        if (status >= 200 && status < 300) {
            return new DeliveryResult.Success();
        }
        FailureClass failureClass;
        if (status == 429) {
            return new DeliveryResult.Failure(FailureClass.RATE_LIMITED, retryAfter(retryAfter, now));
        } else if (status == 401 || status == 403) {
            failureClass = FailureClass.AUTH_ERROR;
        } else if (status == 404 || status == 410) {
            failureClass = FailureClass.INVALID_RECIPIENT;
        } else if (status >= 300 && status < 500) {
            failureClass = FailureClass.PERMANENT_REJECTION;
        } else {
            failureClass = FailureClass.TRANSIENT;
        }
        return new DeliveryResult.Failure(failureClass);
    }

    /** {@code Retry-After} as delta-seconds or an HTTP date; {@code null} when absent or unparsable. */
    static Duration retryAfter(String value, Instant now) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.matches("\\d{1,9}")) {
            return Duration.ofSeconds(Long.parseLong(trimmed));
        }
        try {
            Instant at = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration wait = Duration.between(now, at);
            return wait.isNegative() ? Duration.ZERO : wait;
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}

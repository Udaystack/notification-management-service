package com.nms.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validation;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RequestValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RequestValidator validator = new RequestValidator(
            Validation.buildDefaultValidatorFactory().getValidator(), mapper, 3);

    private ObjectNode valid() {
        ObjectNode body = mapper.createObjectNode()
                .put("sourceSystem", "billing")
                .put("eventId", "INV-42")
                .put("type", "TRANSACTIONAL")
                .put("severity", "MEDIUM")
                .put("priority", "NORMAL")
                .put("subject", "Invoice ready")
                .put("body", "Your invoice is ready.");
        body.putArray("recipients").add("cust-1001");
        body.putArray("channels").add("EMAIL");
        return body;
    }

    private RequestValidator.Result validate(ObjectNode body) {
        return validator.validate(body, "key-1");
    }

    @Test
    void validRequestProducesCommand() {
        RequestValidator.Result result = validate(valid());
        assertThat(result.isValid()).isTrue();
        assertThat(result.command().recipients()).containsExactly("cust-1001");
    }

    @Test
    void missingRecipients() {
        ObjectNode body = valid();
        body.putArray("recipients");
        assertThat(validate(body).errors()).extracting(FieldError::field).containsExactly("recipients");
    }

    @Test
    void unknownEnumValue() {
        RequestValidator.Result result = validate(valid().put("severity", "URGENT"));
        assertThat(result.errors()).singleElement().satisfies(e -> {
            assertThat(e.field()).isEqualTo("severity");
            assertThat(e.message()).contains("LOW", "MEDIUM", "HIGH", "CRITICAL");
        });
    }

    @Test
    void expiryBeforeSchedule() {
        ObjectNode body = valid().put("scheduledAt", "2030-01-01T10:00:00Z").put("expiresAt", "2030-01-01T10:00:00Z");
        assertThat(validate(body).errors()).singleElement()
                .satisfies(e -> assertThat(e).isEqualTo(new FieldError("expiresAt", "must be after scheduledAt")));
    }

    @Test
    void missingIdempotencyKey() {
        assertThat(validator.validate(valid(), null).errors()).extracting(FieldError::field)
                .containsExactly("Idempotency-Key");
    }

    @Test
    void recipientLimitExceeded() {
        ObjectNode body = valid();
        ArrayNode recipients = body.putArray("recipients");
        IntStream.range(0, 4).forEach(i -> recipients.add("cust-" + i));
        assertThat(validate(body).errors()).singleElement()
                .satisfies(e -> assertThat(e.message()).contains("at most 3 recipients"));
    }

    @Test
    void duplicateRecipientsAndChannelsAreRejected() {
        ObjectNode body = valid();
        body.putArray("recipients").add("cust-1001").add("cust-1001");
        body.putArray("channels").add("EMAIL").add("EMAIL");
        assertThat(validate(body).errors()).extracting(FieldError::field)
                .containsExactlyInAnyOrder("recipients", "channels");
    }

    @Test
    void unknownFieldIsRejected() {
        assertThat(validate(valid().put("sevrity", "LOW")).errors()).extracting(FieldError::field)
                .containsExactly("sevrity");
    }

    @Test
    void everyInvalidFieldIsListed() {
        ObjectNode body = valid().put("severity", "URGENT").put("subject", "");
        body.putArray("recipients");
        assertThat(validator.validate(body, null).errors()).extracting(FieldError::field)
                .containsExactlyInAnyOrder("Idempotency-Key", "recipients", "severity", "subject");
    }
}

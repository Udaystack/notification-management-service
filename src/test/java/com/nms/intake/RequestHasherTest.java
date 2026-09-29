package com.nms.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.nms.common.domain.Channel;
import com.nms.common.domain.NotificationType;
import com.nms.common.domain.Priority;
import com.nms.common.domain.Severity;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestHasherTest {

    private static SubmitCommand command(String subject, String scheduledAt, List<String> recipients) {
        return new SubmitCommand("billing", "INV-42", NotificationType.TRANSACTIONAL, Severity.MEDIUM, Priority.NORMAL,
                recipients, List.of(Channel.EMAIL), subject, "body",
                OffsetDateTime.parse(scheduledAt).toInstant(), null);
    }

    @Test
    void timeZoneNotationDoesNotChangeTheHash() {
        assertThat(RequestHasher.hash(command("s", "2026-10-01T10:00:00Z", List.of("a"))))
                .isEqualTo(RequestHasher.hash(command("s", "2026-10-01T12:00:00+02:00", List.of("a"))));
    }

    @Test
    void changedFieldChangesTheHash() {
        assertThat(RequestHasher.hash(command("s", "2026-10-01T10:00:00Z", List.of("a"))))
                .isNotEqualTo(RequestHasher.hash(command("other", "2026-10-01T10:00:00Z", List.of("a"))));
    }

    @Test
    void listOrderChangesTheHash() {
        assertThat(RequestHasher.hash(command("s", "2026-10-01T10:00:00Z", List.of("a", "b"))))
                .isNotEqualTo(RequestHasher.hash(command("s", "2026-10-01T10:00:00Z", List.of("b", "a"))));
    }
}

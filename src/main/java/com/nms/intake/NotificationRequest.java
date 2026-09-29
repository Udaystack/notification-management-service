package com.nms.intake;

import com.nms.common.domain.Channel;
import com.nms.common.domain.NotificationType;
import com.nms.common.domain.Priority;
import com.nms.common.domain.Severity;
import com.nms.intake.validation.EnumValue;
import com.nms.intake.validation.IsoTimestamp;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import org.hibernate.validator.constraints.UniqueElements;

/**
 * Submission body as received. Enum and timestamp fields stay strings so every invalid field can be reported
 * at once, with the allowed values.
 */
public record NotificationRequest(
        @NotBlank @Size(max = 64) String sourceSystem,
        @NotBlank @Size(max = 128) String eventId,
        @NotNull @EnumValue(NotificationType.class) String type,
        @NotNull @EnumValue(Severity.class) String severity,
        @NotNull @EnumValue(Priority.class) String priority,
        @NotEmpty @UniqueElements List<@NotBlank @Size(max = 64) String> recipients,
        @UniqueElements List<@NotNull @EnumValue(Channel.class) String> channels,
        @NotBlank @Size(max = 200) String subject,
        @NotBlank @Size(max = 10_000) String body,
        @IsoTimestamp String scheduledAt,
        @IsoTimestamp String expiresAt) {

    static final Set<String> FIELDS = Set.of(
            "sourceSystem", "eventId", "type", "severity", "priority", "recipients", "channels",
            "subject", "body", "scheduledAt", "expiresAt");
}

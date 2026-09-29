package com.nms.routing;

import static com.nms.common.domain.Channel.EMAIL;
import static com.nms.common.domain.Channel.PUSH;
import static com.nms.common.domain.Channel.SMS;
import static org.assertj.core.api.Assertions.assertThat;

import com.nms.common.domain.Channel;
import com.nms.common.domain.NotificationType;
import com.nms.common.domain.Severity;
import com.nms.recipient.ChannelPreference;
import com.nms.recipient.RecipientPreferences;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoutingPolicyTest {

    private final RoutingPolicy policy = new RoutingPolicy(
            Map.of(NotificationType.TRANSACTIONAL, List.of(EMAIL),
                    NotificationType.ALERT, List.of(EMAIL, PUSH)),
            Map.of(Severity.CRITICAL, List.of(SMS)),
            EMAIL);

    @Test
    void requestedChannelHonored() {
        RoutingDecision d = policy.route("r", List.of(EMAIL), NotificationType.ALERT, Severity.MEDIUM,
                prefs(EMAIL, "a@example.com", false));

        assertThat(d.selected()).containsExactly(EMAIL);
        assertThat(d.added()).containsExactly(Map.entry(EMAIL, RoutingReason.REQUESTED));
        assertThat(d.outcome()).isNull();
    }

    @Test
    void criticalSeverityEscalates() {
        RoutingDecision d = policy.route("r", List.of(EMAIL), NotificationType.ALERT, Severity.CRITICAL,
                prefs(EMAIL, "a@example.com", false, SMS, "+1-555-000-0000", false));

        assertThat(d.selected()).containsExactly(EMAIL, SMS);
        assertThat(d.added()).containsEntry(SMS, RoutingReason.SEVERITY_ESCALATION);
    }

    @Test
    void optOutRespectedWithFallback() {
        RoutingDecision d = policy.route("r", List.of(SMS), NotificationType.ALERT, Severity.LOW,
                prefs(EMAIL, "a@example.com", false, SMS, "+1-555-000-0000", true));

        assertThat(d.selected()).containsExactly(EMAIL);
        assertThat(d.added()).containsExactly(Map.entry(EMAIL, RoutingReason.FALLBACK));
        assertThat(d.removed()).containsExactly(Map.entry(SMS, RoutingReason.RECIPIENT_OPT_OUT));
    }

    @Test
    void noDeliverableChannelForOneRecipient() {
        RoutingDecision d = policy.route("r", List.of(PUSH), NotificationType.ALERT, Severity.LOW,
                prefs(PUSH, "token-1234", true));

        assertThat(d.selected()).isEmpty();
        assertThat(d.hasDeliveries()).isFalse();
        assertThat(d.outcome()).isEqualTo(RoutingReason.NO_ELIGIBLE_CHANNEL);
    }

    @Test
    void noDeliverableRecipientAtAll() {
        List<RoutingDecision> decisions = List.of(
                policy.route("r1", List.of(PUSH), NotificationType.ALERT, Severity.LOW, prefs(PUSH, "token-1234", true)),
                policy.route("r2", List.of(EMAIL), NotificationType.ALERT, Severity.LOW, null));

        assertThat(decisions).noneMatch(RoutingDecision::hasDeliveries);
    }

    @Test
    void decisionReasonsRecorded() {
        RoutingDecision d = policy.route("r", List.of(), NotificationType.ALERT, Severity.CRITICAL,
                prefs(EMAIL, "ops@example.com", false, SMS, "+1-555-000-1111", false, PUSH, "token-1004", true));

        assertThat(d.selected()).containsExactly(EMAIL, SMS);
        assertThat(d.added()).containsEntry(EMAIL, RoutingReason.TYPE_DEFAULT)
                .containsEntry(SMS, RoutingReason.SEVERITY_ESCALATION);
        assertThat(d.removed()).containsExactly(Map.entry(PUSH, RoutingReason.RECIPIENT_OPT_OUT));
    }

    @Test
    void unknownRecipient() {
        RoutingDecision d = policy.route("nobody", List.of(EMAIL), NotificationType.ALERT, Severity.LOW, null);

        assertThat(d.selected()).isEmpty();
        assertThat(d.outcome()).isEqualTo(RoutingReason.UNKNOWN_RECIPIENT);
    }

    private static RecipientPreferences prefs(Object... triples) {
        Map<Channel, ChannelPreference> channels = new EnumMap<>(Channel.class);
        for (int i = 0; i < triples.length; i += 3) {
            channels.put((Channel) triples[i], new ChannelPreference((String) triples[i + 1], (Boolean) triples[i + 2]));
        }
        return new RecipientPreferences("r", channels);
    }
}

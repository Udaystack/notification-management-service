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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the complete routing decision (selected channels, reasons, outcome) for the existing channels, so that adding
 * the webhook channel shows up as a reviewed diff here if it changes any of them.
 */
class RoutingCharacterizationTest {

    private final RoutingPolicy policy = new RoutingPolicy(
            Map.of(NotificationType.TRANSACTIONAL, List.of(EMAIL),
                    NotificationType.ALERT, List.of(EMAIL, PUSH)),
            Map.of(Severity.CRITICAL, List.of(SMS)),
            EMAIL);

    private static final RecipientPreferences ALL = prefs(
            EMAIL, "a@example.com", false, SMS, "+1-555-000-0000", false, PUSH, "token-1234", false);

    @Test
    void requestedChannel() {
        assertThat(policy.route("r", List.of(EMAIL), NotificationType.ALERT, Severity.MEDIUM, ALL))
                .isEqualTo(decision(List.of(EMAIL), added(EMAIL, RoutingReason.REQUESTED), Map.of(), null));
    }

    @Test
    void typeDefault() {
        assertThat(policy.route("r", List.of(), NotificationType.TRANSACTIONAL, Severity.LOW, ALL))
                .isEqualTo(decision(List.of(EMAIL), added(EMAIL, RoutingReason.TYPE_DEFAULT), Map.of(), null));
        assertThat(policy.route("r", List.of(), NotificationType.ALERT, Severity.LOW, ALL))
                .isEqualTo(decision(List.of(EMAIL, PUSH),
                        added(EMAIL, RoutingReason.TYPE_DEFAULT, PUSH, RoutingReason.TYPE_DEFAULT), Map.of(), null));
    }

    @Test
    void criticalEscalation() {
        assertThat(policy.route("r", List.of(EMAIL), NotificationType.ALERT, Severity.CRITICAL, ALL))
                .isEqualTo(decision(List.of(EMAIL, SMS),
                        added(EMAIL, RoutingReason.REQUESTED, SMS, RoutingReason.SEVERITY_ESCALATION), Map.of(), null));
        assertThat(policy.route("r", List.of(), NotificationType.TRANSACTIONAL, Severity.CRITICAL, ALL))
                .isEqualTo(decision(List.of(EMAIL, SMS),
                        added(EMAIL, RoutingReason.TYPE_DEFAULT, SMS, RoutingReason.SEVERITY_ESCALATION), Map.of(), null));
    }

    @Test
    void optOutWithFallback() {
        assertThat(policy.route("r", List.of(SMS), NotificationType.ALERT, Severity.LOW,
                prefs(EMAIL, "a@example.com", false, SMS, "+1-555-000-0000", true)))
                .isEqualTo(decision(List.of(EMAIL), added(EMAIL, RoutingReason.FALLBACK),
                        Map.of(SMS, RoutingReason.RECIPIENT_OPT_OUT), null));
    }

    @Test
    void missingAddressWithFallback() {
        assertThat(policy.route("r", List.of(PUSH), NotificationType.ALERT, Severity.LOW,
                prefs(EMAIL, "a@example.com", false)))
                .isEqualTo(decision(List.of(EMAIL), added(EMAIL, RoutingReason.FALLBACK),
                        Map.of(PUSH, RoutingReason.NO_ADDRESS), null));
    }

    @Test
    void noEligibleChannel() {
        assertThat(policy.route("r", List.of(PUSH), NotificationType.ALERT, Severity.LOW,
                prefs(PUSH, "token-1234", true)))
                .isEqualTo(decision(List.of(), Map.of(), Map.of(PUSH, RoutingReason.RECIPIENT_OPT_OUT),
                        RoutingReason.NO_ELIGIBLE_CHANNEL));
    }

    @Test
    void mixedReasons() {
        assertThat(policy.route("r", List.of(), NotificationType.ALERT, Severity.CRITICAL,
                prefs(EMAIL, "ops@example.com", false, SMS, "+1-555-000-1111", false, PUSH, "token-1004", true)))
                .isEqualTo(decision(List.of(EMAIL, SMS),
                        added(EMAIL, RoutingReason.TYPE_DEFAULT, SMS, RoutingReason.SEVERITY_ESCALATION),
                        Map.of(PUSH, RoutingReason.RECIPIENT_OPT_OUT), null));
    }

    @Test
    void unknownRecipient() {
        assertThat(policy.route("nobody", List.of(EMAIL), NotificationType.ALERT, Severity.LOW, null))
                .isEqualTo(new RoutingDecision("nobody", List.of(), Map.of(), Map.of(), RoutingReason.UNKNOWN_RECIPIENT));
    }

    private static RoutingDecision decision(List<Channel> selected, Map<Channel, RoutingReason> added,
            Map<Channel, RoutingReason> removed, RoutingReason outcome) {
        return new RoutingDecision("r", selected, added, removed, outcome);
    }

    private static Map<Channel, RoutingReason> added(Object... pairs) {
        Map<Channel, RoutingReason> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((Channel) pairs[i], (RoutingReason) pairs[i + 1]);
        }
        return map;
    }

    private static RecipientPreferences prefs(Object... triples) {
        Map<Channel, ChannelPreference> channels = new EnumMap<>(Channel.class);
        for (int i = 0; i < triples.length; i += 3) {
            channels.put((Channel) triples[i], new ChannelPreference((String) triples[i + 1], (Boolean) triples[i + 2]));
        }
        return new RecipientPreferences("r", channels);
    }
}

package com.nms.channel.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.nms.common.domain.FailureClass;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhookTargetPolicyTest {

    /** Fake DNS: literal IPs resolve to themselves, known names to fixed addresses, anything else is unknown. */
    private static final WebhookTargetPolicy.AddressResolver DNS = host -> {
        Map<String, String[]> names = Map.of(
                "hooks.example.com", new String[] {"93.184.216.34"},
                "internal.example.com", new String[] {"10.0.0.5"},
                "mixed.example.com", new String[] {"93.184.216.34", "192.168.1.10"},
                "v6.example.com", new String[] {"2606:4700:4700::1111"});
        if (host.matches("[0-9.]+") || host.contains(":")) {
            return new InetAddress[] {InetAddress.getByName(host)};
        }
        String[] ips = names.get(host);
        if (ips == null) {
            throw new UnknownHostException(host);
        }
        InetAddress[] result = new InetAddress[ips.length];
        for (int i = 0; i < ips.length; i++) {
            result[i] = InetAddress.getByName(ips[i]);
        }
        return result;
    };

    private final WebhookTargetPolicy strict = new WebhookTargetPolicy(false, DNS);
    private final WebhookTargetPolicy development = new WebhookTargetPolicy(true, DNS);

    private static FailureClass failure(WebhookTargetPolicy.Decision decision) {
        return decision.failure();
    }

    @Test
    void publicHttpsTargetAllowed() {
        assertThat(strict.check("https://hooks.example.com/notify").allowed()).isTrue();
        assertThat(strict.check("https://v6.example.com/notify").allowed()).isTrue();
        assertThat(strict.check("https://[2606:4700:4700::1111]/notify").allowed()).isTrue();
    }

    @Test
    void plainHttpTargetRejected() {
        assertThat(failure(strict.check("http://hooks.example.com/notify"))).isEqualTo(FailureClass.INVALID_RECIPIENT);
        assertThat(failure(strict.check("ftp://hooks.example.com/notify"))).isEqualTo(FailureClass.INVALID_RECIPIENT);
    }

    @Test
    void privateAddressTargetRejected() {
        assertThat(failure(strict.check("https://127.0.0.1/notify"))).isEqualTo(FailureClass.INVALID_RECIPIENT);
        assertThat(failure(strict.check("https://internal.example.com/notify")))
                .isEqualTo(FailureClass.INVALID_RECIPIENT);
        assertThat(failure(strict.check("https://mixed.example.com/notify")))
                .as("one private address is enough to reject").isEqualTo(FailureClass.INVALID_RECIPIENT);
        for (String ip : new String[] {"0.0.0.0", "172.16.0.1", "192.168.0.1", "169.254.169.254", "224.0.0.1"}) {
            assertThat(failure(strict.check("https://" + ip + "/notify"))).as(ip)
                    .isEqualTo(FailureClass.INVALID_RECIPIENT);
        }
    }

    @Test
    void privateIpv6TargetRejected() {
        for (String ip : new String[] {"::1", "::", "fe80::1", "fd00::1", "fc00::1", "ff02::1", "::ffff:10.0.0.1"}) {
            assertThat(failure(strict.check("https://[" + ip + "]/notify"))).as(ip)
                    .isEqualTo(FailureClass.INVALID_RECIPIENT);
        }
    }

    @Test
    void unknownHostIsTransient() {
        assertThat(failure(strict.check("https://nowhere.invalid/notify"))).isEqualTo(FailureClass.TRANSIENT);
    }

    @Test
    void unparsableTargetRejected() {
        for (String url : new String[] {"not a url", "https://", "/relative", "", "https:///path"}) {
            assertThat(failure(strict.check(url))).as(url).isEqualTo(FailureClass.INVALID_RECIPIENT);
        }
        assertThat(failure(strict.check(null))).isEqualTo(FailureClass.INVALID_RECIPIENT);
    }

    @Test
    void privateHostsAllowedForDevelopment() {
        assertThat(development.check("http://localhost:9099/hooks/cust-3001").target())
                .hasToString("http://localhost:9099/hooks/cust-3001");
        assertThat(development.check("https://10.0.0.5/notify").allowed()).isTrue();
        assertThat(failure(development.check("ftp://localhost/notify"))).isEqualTo(FailureClass.INVALID_RECIPIENT);
    }
}

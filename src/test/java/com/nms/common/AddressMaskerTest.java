package com.nms.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.nms.common.domain.Channel;
import org.junit.jupiter.api.Test;

class AddressMaskerTest {

    @Test
    void addressMasked() {
        assertThat(AddressMasker.mask(Channel.EMAIL, "jane.doe@example.com")).isEqualTo("j***@example.com");
    }

    @Test
    void webhookAddressMaskedToSchemeAndHost() {
        assertThat(AddressMasker.mask(Channel.WEBHOOK,
                "https://user:pw@Hooks.Example.com:8443/notify/cust-1?token=abc#frag"))
                .isEqualTo("https://hooks.example.com/***");
        assertThat(AddressMasker.mask(Channel.WEBHOOK, "http://localhost:9099/hooks/cust-3001"))
                .isEqualTo("http://localhost/***");
        assertThat(AddressMasker.mask(Channel.WEBHOOK, "not a url")).isEqualTo("***");
        assertThat(AddressMasker.mask(Channel.WEBHOOK, "/relative/path")).isEqualTo("***");
        assertThat(AddressMasker.mask(Channel.WEBHOOK, "")).isEqualTo("***");
    }
}

package com.nms.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.nms.common.domain.Channel;
import org.junit.jupiter.api.Test;

/** Pins the masking of the existing channels before the webhook channel is added. */
class AddressMaskerCharacterizationTest {

    @Test
    void existingChannels() {
        assertThat(AddressMasker.mask(Channel.EMAIL, "jane.doe@example.com")).isEqualTo("j***@example.com");
        assertThat(AddressMasker.mask(Channel.SMS, "+1-555-867-1234")).isEqualTo("***-***-1234");
        assertThat(AddressMasker.mask(Channel.PUSH, "device-token-1001")).isEqualTo("***1001");
    }

    @Test
    void blankAndMalformed() {
        for (Channel channel : new Channel[] {Channel.EMAIL, Channel.SMS, Channel.PUSH}) {
            assertThat(AddressMasker.mask(channel, null)).isEqualTo("***");
            assertThat(AddressMasker.mask(channel, "  ")).isEqualTo("***");
        }
        assertThat(AddressMasker.mask(Channel.EMAIL, "no-at-sign")).isEqualTo("***");
        assertThat(AddressMasker.mask(Channel.EMAIL, "@example.com")).isEqualTo("***");
        assertThat(AddressMasker.mask(Channel.EMAIL, "jane@")).isEqualTo("***");
        assertThat(AddressMasker.mask(Channel.SMS, "12")).isEqualTo("***");
        assertThat(AddressMasker.mask(Channel.PUSH, "abcd")).isEqualTo("***");
    }
}

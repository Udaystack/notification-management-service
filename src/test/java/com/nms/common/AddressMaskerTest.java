package com.nms.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.nms.common.domain.Channel;
import org.junit.jupiter.api.Test;

class AddressMaskerTest {

    @Test
    void addressMasked() {
        assertThat(AddressMasker.mask(Channel.EMAIL, "jane.doe@example.com")).isEqualTo("j***@example.com");
    }
}

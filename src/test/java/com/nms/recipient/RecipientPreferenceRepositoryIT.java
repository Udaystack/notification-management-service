package com.nms.recipient;

import static org.assertj.core.api.Assertions.assertThat;

import com.nms.IntegrationTestBase;
import com.nms.common.domain.Channel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RecipientPreferenceRepositoryIT extends IntegrationTestBase {

    @Autowired
    RecipientPreferenceRepository repository;

    @Test
    void readsAddressesAndOptOutsFromSeedData() {
        Map<String, RecipientPreferences> prefs = repository.findAll(List.of("cust-1002", "cust-1003", "nobody"));

        assertThat(prefs).containsOnlyKeys("cust-1002", "cust-1003");
        assertThat(prefs.get("cust-1002").channels())
                .containsEntry(Channel.EMAIL, new ChannelPreference("john.smith@example.com", false))
                .containsEntry(Channel.SMS, new ChannelPreference("+1-555-222-3344", true))
                .doesNotContainKey(Channel.PUSH);
        assertThat(prefs.get("cust-1003").channels())
                .containsExactly(Map.entry(Channel.PUSH, new ChannelPreference("device-token-1003", true)));

        assertThat(repository.findAddress("cust-1001", Channel.EMAIL)).contains("jane.doe@example.com");
        assertThat(repository.findAddress("cust-1002", Channel.PUSH)).isEmpty();
    }
}

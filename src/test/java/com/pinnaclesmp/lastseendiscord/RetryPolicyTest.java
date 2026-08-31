package com.pinnaclesmp.lastseendiscord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryPolicyTest {
    @Test
    void exponentialRetryDelayIsBoundedAndIncludesJitter() {
        long first = RetryPolicy.delayMillis(1, RetryableSyncException.NO_SERVER_DELAY);
        long fifth = RetryPolicy.delayMillis(5, RetryableSyncException.NO_SERVER_DELAY);

        assertTrue(first >= 1_000L && first <= 1_250L);
        assertTrue(fifth >= 16_000L && fifth <= 17_000L);
    }

    @Test
    void extremeDiscordRetryValuesAreCapped() {
        long parsed = DiscordWebhookClient.boundedRetryAfterMillis(
                "999999999999999999999999",
                null,
                "{}"
        );
        assertEquals(DiscordWebhookClient.MAX_SERVER_RETRY_DELAY_MILLIS, parsed);

        long scheduled = RetryPolicy.delayMillis(1, parsed);
        assertEquals(RetryPolicy.MAX_DELAY_MILLIS, scheduled);
    }

    @Test
    void invalidRetryValuesUseSafeFallback() {
        assertEquals(2_000L, DiscordWebhookClient.boundedRetryAfterMillis(
                "invalid",
                null,
                "{\"retry_after\":-1}"
        ));
    }

    @Test
    void onlyDiscordUnknownMessage404TriggersRecreation() {
        assertTrue(DiscordWebhookClient.isUnknownMessageResponse(
                404,
                "{\"message\":\"Unknown Message\",\"code\":10008}"
        ));
        assertTrue(!DiscordWebhookClient.isUnknownMessageResponse(
                404,
                "{\"message\":\"Unknown Webhook\",\"code\":10015}"
        ));
        assertTrue(!DiscordWebhookClient.isUnknownMessageResponse(
                401,
                "{\"code\":10008}"
        ));
    }
}

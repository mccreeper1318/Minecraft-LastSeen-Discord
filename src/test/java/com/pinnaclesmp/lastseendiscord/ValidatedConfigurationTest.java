package com.pinnaclesmp.lastseendiscord;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatedConfigurationTest {
    private static final String VALID_WEBHOOK =
            "https://discord.com/api/webhooks/123456789012345678/test_token";

    @Test
    void validatesAndNormalizesBoundaryValues() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("discord.webhook-url", VALID_WEBHOOK);
        raw.put("discord.header", 42);
        raw.put("activity.inactive-after-days", 0);
        raw.put("activity.timestamp-source", "future_value");
        raw.put("updates.interval-minutes", Long.MAX_VALUE);
        raw.put("updates.update-on-join", "yes");
        raw.put("updates.update-on-quit", false);
        raw.put("updates.update-on-enable", true);
        raw.put("updates.event-debounce-seconds", -5);
        raw.put("discord.message-ids", List.of(
                "111111111111111111",
                "111111111111111111",
                "not-an-id"
        ));

        List<String> warnings = new ArrayList<>();
        ValidatedConfiguration configuration = ValidatedConfiguration.parse(new MapValues(raw), warnings::add);

        assertNotNull(configuration.webhookEndpoint());
        assertEquals("discord-webhook:123456789012345678", configuration.webhookIdentity());
        assertEquals("**Server Activity Status**", configuration.header());
        assertEquals(1, configuration.inactiveAfterDays());
        assertEquals(ValidatedConfiguration.TimestampSource.LAST_SEEN, configuration.timestampSource());
        assertEquals(ValidatedConfiguration.MAX_INTERVAL_MINUTES, configuration.intervalMinutes());
        assertTrue(configuration.updateOnJoin());
        assertFalse(configuration.updateOnQuit());
        assertTrue(configuration.updateOnEnable());
        assertEquals(0, configuration.eventDebounceSeconds());
        assertEquals(List.of("111111111111111111"), configuration.legacyMessageIds());

        assertWarningFor(warnings, "discord.header");
        assertWarningFor(warnings, "activity.inactive-after-days");
        assertWarningFor(warnings, "activity.timestamp-source");
        assertWarningFor(warnings, "updates.interval-minutes");
        assertWarningFor(warnings, "updates.update-on-join");
        assertWarningFor(warnings, "updates.event-debounce-seconds");
        assertWarningFor(warnings, "discord.message-ids");
    }

    @Test
    void rejectsInvalidWebhookWithoutLeakingItsSecret() {
        String secret = "super_secret_webhook_token";
        Map<String, Object> raw = Map.of(
                "discord.webhook-url",
                "https://example.com/api/webhooks/123456789012345678/" + secret
        );
        List<String> warnings = new ArrayList<>();

        ValidatedConfiguration configuration = ValidatedConfiguration.parse(new MapValues(raw), warnings::add);

        assertNull(configuration.webhookEndpoint());
        assertFalse(configuration.webhookRebindState());
        assertTrue(configuration.webhookConfigurationMessage().contains("discord.webhook-url"));
        assertTrue(warnings.stream().anyMatch(message -> message.contains("discord.webhook-url")));
        assertFalse(warnings.stream().anyMatch(message -> message.contains(secret)));
        assertFalse(configuration.webhookConfigurationMessage().contains(secret));
    }

    @Test
    void migratesOlderConfigurationDefaultsAndLegacyFields() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("discord.webhook-url", VALID_WEBHOOK);
        raw.put("discord.message-id", "222222222222222222");
        raw.put("discord.include-last-login-date", true);
        raw.put("updates.interval-minutes", 60);

        ValidatedConfiguration configuration = ValidatedConfiguration.parse(
                new MapValues(raw),
                ignored -> { }
        );

        assertTrue(configuration.includeActivityDate());
        assertEquals(ValidatedConfiguration.TimestampSource.LAST_SEEN, configuration.timestampSource());
        assertEquals(ValidatedConfiguration.DEFAULT_INACTIVE_AFTER_DAYS, configuration.inactiveAfterDays());
        assertEquals(60L, configuration.intervalMinutes());
        assertEquals(ValidatedConfiguration.DEFAULT_EVENT_DEBOUNCE_SECONDS, configuration.eventDebounceSeconds());
        assertTrue(configuration.updateOnJoin());
        assertTrue(configuration.updateOnQuit());
        assertTrue(configuration.updateOnEnable());
        assertEquals(List.of("222222222222222222"), configuration.legacyMessageIds());
    }

    @Test
    void malformedLegacyListFallsBackToValidSingleMessageId() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("discord.message-ids", List.of("bad", "also-bad"));
        raw.put("discord.message-id", "333333333333333333");
        List<String> warnings = new ArrayList<>();

        ValidatedConfiguration configuration = ValidatedConfiguration.parse(new MapValues(raw), warnings::add);

        assertEquals(List.of("333333333333333333"), configuration.legacyMessageIds());
        assertWarningFor(warnings, "discord.message-ids");
    }

    @Test
    void clampsSchedulingAndDebounceUpperBounds() {
        Map<String, Object> raw = Map.of(
                "updates.interval-minutes", ValidatedConfiguration.MAX_INTERVAL_MINUTES + 1L,
                "updates.event-debounce-seconds", EventSyncDebouncer.MAX_DEBOUNCE_SECONDS + 1
        );
        List<String> warnings = new ArrayList<>();

        ValidatedConfiguration configuration = ValidatedConfiguration.parse(new MapValues(raw), warnings::add);

        assertEquals(ValidatedConfiguration.MAX_INTERVAL_MINUTES, configuration.intervalMinutes());
        assertEquals(EventSyncDebouncer.MAX_DEBOUNCE_SECONDS, configuration.eventDebounceSeconds());
        assertWarningFor(warnings, "updates.interval-minutes");
        assertWarningFor(warnings, "updates.event-debounce-seconds");
    }

    private static void assertWarningFor(List<String> warnings, String path) {
        assertTrue(warnings.stream().anyMatch(message -> message.contains(path)),
                () -> "Expected a validation warning for " + path + " but got " + warnings);
    }

    private record MapValues(Map<String, Object> values) implements ValidatedConfiguration.Values {
        @Override
        public Object get(String path) {
            return values.get(path);
        }

        @Override
        public boolean contains(String path) {
            return values.containsKey(path);
        }
    }
}

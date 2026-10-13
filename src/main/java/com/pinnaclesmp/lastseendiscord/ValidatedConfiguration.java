package com.pinnaclesmp.lastseendiscord;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

final class ValidatedConfiguration {
    static final long DEFAULT_INTERVAL_MINUTES = 1440L;
    static final long MAX_INTERVAL_MINUTES = 525_600L;
    static final int DEFAULT_INACTIVE_AFTER_DAYS = 30;
    static final int DEFAULT_EVENT_DEBOUNCE_SECONDS = EventSyncDebouncer.DEFAULT_DEBOUNCE_SECONDS;

    private static final String WEBHOOK_PLACEHOLDER = "PUT_DISCORD_WEBHOOK_URL_HERE";
    private static final String DEFAULT_HEADER = "**Server Activity Status**";

    private final WebhookEndpoint webhookEndpoint;
    private final boolean webhookRebindState;
    private final String webhookConfigurationMessage;
    private final String header;
    private final boolean includeActivityDate;
    private final int inactiveAfterDays;
    private final TimestampSource timestampSource;
    private final boolean whitelistOnly;
    private final Set<UUID> excludedPlayerUuids;
    private final Set<String> excludedPlayerNames;
    private final ActivityFilter activityFilter;
    private final long intervalMinutes;
    private final boolean updateOnJoin;
    private final boolean updateOnQuit;
    private final boolean updateOnEnable;
    private final int eventDebounceSeconds;
    private final List<String> legacyMessageIds;

    private ValidatedConfiguration(
            WebhookEndpoint webhookEndpoint,
            boolean webhookRebindState,
            String webhookConfigurationMessage,
            String header,
            boolean includeActivityDate,
            int inactiveAfterDays,
            TimestampSource timestampSource,
            boolean whitelistOnly,
            Set<UUID> excludedPlayerUuids,
            Set<String> excludedPlayerNames,
            ActivityFilter activityFilter,
            long intervalMinutes,
            boolean updateOnJoin,
            boolean updateOnQuit,
            boolean updateOnEnable,
            int eventDebounceSeconds,
            List<String> legacyMessageIds
    ) {
        this.webhookEndpoint = webhookEndpoint;
        this.webhookRebindState = webhookRebindState;
        this.webhookConfigurationMessage = webhookConfigurationMessage;
        this.header = header;
        this.includeActivityDate = includeActivityDate;
        this.inactiveAfterDays = inactiveAfterDays;
        this.timestampSource = timestampSource;
        this.whitelistOnly = whitelistOnly;
        this.excludedPlayerUuids = Set.copyOf(excludedPlayerUuids);
        this.excludedPlayerNames = Set.copyOf(excludedPlayerNames);
        this.activityFilter = activityFilter;
        this.intervalMinutes = intervalMinutes;
        this.updateOnJoin = updateOnJoin;
        this.updateOnQuit = updateOnQuit;
        this.updateOnEnable = updateOnEnable;
        this.eventDebounceSeconds = eventDebounceSeconds;
        this.legacyMessageIds = List.copyOf(legacyMessageIds);
    }

    static ValidatedConfiguration parse(Values values, Consumer<String> warningSink) {
        WebhookSetting webhook = parseWebhook(values.get("discord.webhook-url"), warningSink);
        String header = readString(values, "discord.header", DEFAULT_HEADER, warningSink);
        boolean includeActivityDate = readActivityDateSetting(values, warningSink);
        int inactiveAfterDays = (int) readWholeNumber(
                values,
                "activity.inactive-after-days",
                DEFAULT_INACTIVE_AFTER_DAYS,
                1L,
                Integer.MAX_VALUE,
                warningSink
        );
        TimestampSource timestampSource = readTimestampSource(values, warningSink);
        boolean whitelistOnly = readBoolean(values, "filters.whitelist-only", false, warningSink);
        Set<UUID> excludedPlayerUuids = readExcludedPlayerUuids(values, warningSink);
        Set<String> excludedPlayerNames = readExcludedPlayerNames(values, warningSink);
        ActivityFilter activityFilter = readActivityFilter(values, warningSink);
        long intervalMinutes = readWholeNumber(
                values,
                "updates.interval-minutes",
                DEFAULT_INTERVAL_MINUTES,
                1L,
                MAX_INTERVAL_MINUTES,
                warningSink
        );
        boolean updateOnJoin = readBoolean(values, "updates.update-on-join", true, warningSink);
        boolean updateOnQuit = readBoolean(values, "updates.update-on-quit", true, warningSink);
        boolean updateOnEnable = readBoolean(values, "updates.update-on-enable", true, warningSink);
        int eventDebounceSeconds = (int) readWholeNumber(
                values,
                "updates.event-debounce-seconds",
                DEFAULT_EVENT_DEBOUNCE_SECONDS,
                0L,
                EventSyncDebouncer.MAX_DEBOUNCE_SECONDS,
                warningSink
        );
        List<String> legacyMessageIds = readLegacyMessageIds(values, warningSink);

        return new ValidatedConfiguration(
                webhook.endpoint(),
                webhook.rebindState(),
                webhook.configurationMessage(),
                header,
                includeActivityDate,
                inactiveAfterDays,
                timestampSource,
                whitelistOnly,
                excludedPlayerUuids,
                excludedPlayerNames,
                activityFilter,
                intervalMinutes,
                updateOnJoin,
                updateOnQuit,
                updateOnEnable,
                eventDebounceSeconds,
                legacyMessageIds
        );
    }

    private static WebhookSetting parseWebhook(Object raw, Consumer<String> warningSink) {
        if (raw == null) {
            return WebhookSetting.unconfigured();
        }
        if (!(raw instanceof String configuredUrl)) {
            warningSink.accept("discord.webhook-url must be a string containing a complete HTTPS Discord webhook URL. "
                    + "Discord synchronization is disabled until this setting is corrected.");
            return WebhookSetting.invalid();
        }

        String trimmed = configuredUrl.trim();
        if (trimmed.isEmpty() || WEBHOOK_PLACEHOLDER.equals(trimmed)) {
            return WebhookSetting.unconfigured();
        }

        try {
            return WebhookSetting.configured(WebhookEndpoint.parse(trimmed));
        } catch (SyncException ex) {
            warningSink.accept("discord.webhook-url is invalid. Configure a complete official HTTPS Discord webhook URL. "
                    + "Discord synchronization is disabled until this setting is corrected.");
            return WebhookSetting.invalid();
        }
    }

    private static String readString(
            Values values,
            String path,
            String defaultValue,
            Consumer<String> warningSink
    ) {
        Object raw = values.get(path);
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof String stringValue) {
            return stringValue.trim();
        }
        warningSink.accept(path + " must be a string; using the default value.");
        return defaultValue;
    }

    private static boolean readActivityDateSetting(Values values, Consumer<String> warningSink) {
        if (values.contains("discord.include-last-seen-date")) {
            return readBoolean(values, "discord.include-last-seen-date", false, warningSink);
        }
        if (values.contains("discord.include-last-login-date")) {
            return readBoolean(values, "discord.include-last-login-date", false, warningSink);
        }
        return false;
    }

    private static boolean readBoolean(
            Values values,
            String path,
            boolean defaultValue,
            Consumer<String> warningSink
    ) {
        Object raw = values.get(path);
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean booleanValue) {
            return booleanValue;
        }
        warningSink.accept(path + " must be true or false; using the default value " + defaultValue + ".");
        return defaultValue;
    }

    private static long readWholeNumber(
            Values values,
            String path,
            long defaultValue,
            long minimum,
            long maximum,
            Consumer<String> warningSink
    ) {
        Object raw = values.get(path);
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Number number)) {
            warningSink.accept(path + " must be a whole number between " + minimum + " and " + maximum
                    + "; using the default value " + defaultValue + ".");
            return defaultValue;
        }

        double numericValue = number.doubleValue();
        if (!Double.isFinite(numericValue) || numericValue != Math.rint(numericValue)) {
            warningSink.accept(path + " must be a whole number between " + minimum + " and " + maximum
                    + "; using the default value " + defaultValue + ".");
            return defaultValue;
        }

        if (numericValue < minimum) {
            warningSink.accept(path + " is below the supported minimum of " + minimum + "; using " + minimum + ".");
            return minimum;
        }
        if (numericValue > maximum) {
            warningSink.accept(path + " exceeds the supported maximum of " + maximum + "; using " + maximum + ".");
            return maximum;
        }
        return number.longValue();
    }

    private static TimestampSource readTimestampSource(Values values, Consumer<String> warningSink) {
        Object raw = values.get("activity.timestamp-source");
        if (raw == null) {
            return TimestampSource.LAST_SEEN;
        }
        if (!(raw instanceof String stringValue) || stringValue.isBlank()) {
            warningSink.accept("activity.timestamp-source must be LAST_SEEN or LAST_LOGIN; using LAST_SEEN.");
            return TimestampSource.LAST_SEEN;
        }
        try {
            return TimestampSource.valueOf(stringValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            warningSink.accept("activity.timestamp-source must be LAST_SEEN or LAST_LOGIN; using LAST_SEEN.");
            return TimestampSource.LAST_SEEN;
        }
    }

    private static Set<UUID> readExcludedPlayerUuids(Values values, Consumer<String> warningSink) {
        Object raw = values.get("filters.excluded-uuids");
        if (raw == null) {
            return Set.of();
        }
        if (!(raw instanceof List<?> entries)) {
            warningSink.accept("filters.excluded-uuids must be a list of UUID strings; using an empty exclusion list.");
            return Set.of();
        }

        Set<UUID> result = new LinkedHashSet<>();
        boolean ignoredEntry = false;
        for (Object entry : entries) {
            if (!(entry instanceof String stringValue) || stringValue.isBlank()) {
                ignoredEntry = true;
                continue;
            }
            try {
                result.add(UUID.fromString(stringValue.trim()));
            } catch (IllegalArgumentException ex) {
                ignoredEntry = true;
            }
        }
        if (ignoredEntry) {
            warningSink.accept("filters.excluded-uuids contains invalid entries; those entries will be ignored.");
        }
        return Set.copyOf(result);
    }

    private static Set<String> readExcludedPlayerNames(Values values, Consumer<String> warningSink) {
        Object raw = values.get("filters.excluded-names");
        if (raw == null) {
            return Set.of();
        }
        if (!(raw instanceof List<?> entries)) {
            warningSink.accept("filters.excluded-names must be a list of player names; using an empty exclusion list.");
            return Set.of();
        }

        Set<String> result = new LinkedHashSet<>();
        boolean ignoredEntry = false;
        for (Object entry : entries) {
            if (!(entry instanceof String stringValue) || stringValue.isBlank()) {
                ignoredEntry = true;
                continue;
            }
            result.add(stringValue.trim().toLowerCase(Locale.ROOT));
        }
        if (ignoredEntry) {
            warningSink.accept("filters.excluded-names contains invalid entries; those entries will be ignored.");
        }
        return Set.copyOf(result);
    }

    private static ActivityFilter readActivityFilter(Values values, Consumer<String> warningSink) {
        Object raw = values.get("filters.activity");
        if (raw == null) {
            return ActivityFilter.ALL;
        }
        if (!(raw instanceof String stringValue) || stringValue.isBlank()) {
            warningSink.accept("filters.activity must be ALL, ACTIVE, or INACTIVE; using ALL.");
            return ActivityFilter.ALL;
        }
        try {
            return ActivityFilter.valueOf(stringValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            warningSink.accept("filters.activity must be ALL, ACTIVE, or INACTIVE; using ALL.");
            return ActivityFilter.ALL;
        }
    }

    private static List<String> readLegacyMessageIds(Values values, Consumer<String> warningSink) {
        List<String> configuredList = new ArrayList<>();
        Object rawList = values.get("discord.message-ids");
        if (rawList != null) {
            if (rawList instanceof List<?> entries) {
                Set<String> unique = new LinkedHashSet<>();
                boolean ignoredEntry = false;
                for (Object entry : entries) {
                    if (!(entry instanceof String stringValue)) {
                        ignoredEntry = true;
                        continue;
                    }
                    String messageId = stringValue.trim();
                    if (!WebhookEndpoint.isValidMessageId(messageId) || !unique.add(messageId)) {
                        ignoredEntry = true;
                    }
                }
                configuredList.addAll(unique);
                if (ignoredEntry) {
                    warningSink.accept("discord.message-ids contains malformed or duplicate message IDs; invalid entries "
                            + "will be ignored during legacy migration.");
                }
            } else {
                warningSink.accept("discord.message-ids must be a list of Discord message IDs; ignoring this legacy "
                        + "setting during migration.");
            }
        }

        Object rawSingle = values.get("discord.message-id");
        String singleId = null;
        if (rawSingle != null) {
            if (rawSingle instanceof String stringValue) {
                String trimmed = stringValue.trim();
                if (!trimmed.isEmpty()) {
                    if (WebhookEndpoint.isValidMessageId(trimmed)) {
                        singleId = trimmed;
                    } else {
                        warningSink.accept("discord.message-id is not a valid Discord message ID; ignoring this legacy "
                                + "setting during migration.");
                    }
                }
            } else {
                warningSink.accept("discord.message-id must be a Discord message ID string; ignoring this legacy "
                        + "setting during migration.");
            }
        }

        if (!configuredList.isEmpty()) {
            return List.copyOf(configuredList);
        }
        return singleId == null ? List.of() : List.of(singleId);
    }

    WebhookEndpoint webhookEndpoint() {
        return webhookEndpoint;
    }

    String webhookIdentity() {
        return webhookEndpoint == null ? null : webhookEndpoint.stateIdentity();
    }

    boolean webhookRebindState() {
        return webhookRebindState;
    }

    String webhookConfigurationMessage() {
        return webhookConfigurationMessage;
    }

    String header() {
        return header;
    }

    boolean includeActivityDate() {
        return includeActivityDate;
    }

    int inactiveAfterDays() {
        return inactiveAfterDays;
    }

    TimestampSource timestampSource() {
        return timestampSource;
    }

    boolean includesPlayer(UUID uuid, String name, boolean whitelisted) {
        if (whitelistOnly && !whitelisted) {
            return false;
        }
        if (uuid != null && excludedPlayerUuids.contains(uuid)) {
            return false;
        }
        return name == null || !excludedPlayerNames.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    ActivityFilter activityFilter() {
        return activityFilter;
    }

    long intervalMinutes() {
        return intervalMinutes;
    }

    boolean updateOnJoin() {
        return updateOnJoin;
    }

    boolean updateOnQuit() {
        return updateOnQuit;
    }

    boolean updateOnEnable() {
        return updateOnEnable;
    }

    int eventDebounceSeconds() {
        return eventDebounceSeconds;
    }

    List<String> legacyMessageIds() {
        return legacyMessageIds;
    }

    enum ActivityFilter {
        ALL {
            @Override
            boolean includes(boolean active) {
                return true;
            }
        },
        ACTIVE {
            @Override
            boolean includes(boolean active) {
                return active;
            }
        },
        INACTIVE {
            @Override
            boolean includes(boolean active) {
                return !active;
            }
        };

        abstract boolean includes(boolean active);
    }

    enum TimestampSource {
        LAST_SEEN("last seen"),
        LAST_LOGIN("last login");

        private final String displayName;

        TimestampSource(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }
    }

    interface Values {
        Object get(String path);

        boolean contains(String path);
    }

    private record WebhookSetting(
            WebhookEndpoint endpoint,
            boolean rebindState,
            String configurationMessage
    ) {
        static WebhookSetting configured(WebhookEndpoint endpoint) {
            return new WebhookSetting(endpoint, true, null);
        }

        static WebhookSetting unconfigured() {
            return new WebhookSetting(
                    null,
                    true,
                    "Skipping Discord sync: discord.webhook-url is not configured."
            );
        }

        static WebhookSetting invalid() {
            return new WebhookSetting(
                    null,
                    false,
                    "Skipping Discord sync: discord.webhook-url is invalid. Configure a complete official HTTPS "
                            + "Discord webhook URL, then run /lsd reload."
            );
        }
    }
}

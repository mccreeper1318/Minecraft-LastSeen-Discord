package com.pinnaclesmp.lastseendiscord;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordActivityMessageBuilderTest {
    @Test
    void rendersLargeOfflinePlayerHistoryWithinReasonableTime() {
        DiscordActivityMessageBuilder.Settings settings = new DiscordActivityMessageBuilder.Settings(
                30,
                false,
                "last seen",
                "Large history test"
        );
        long capturedAtMillis = 1_800_000_000_000L;
        List<DiscordActivityMessageBuilder.PlayerActivity> players = new ArrayList<>(50_000);
        for (int index = 49_999; index >= 0; index--) {
            players.add(new DiscordActivityMessageBuilder.PlayerActivity(
                    "Player" + String.format("%05d", index),
                    capturedAtMillis - (index % 60) * 24L * 60L * 60L * 1000L
            ));
        }

        List<String> chunks = assertTimeout(
                Duration.ofSeconds(5),
                () -> DiscordActivityMessageBuilder.build(settings, players, capturedAtMillis)
        );

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= DiscordActivityMessageBuilder.DISCORD_CHUNK_MAX));

        String rendered = String.join("\n", chunks);
        int firstPlayer = rendered.indexOf("Player00000");
        int lastPlayer = rendered.indexOf("Player49999");
        assertTrue(firstPlayer >= 0);
        assertTrue(lastPlayer > firstPlayer);
        assertTrue(rendered.contains("Updated: <t:1800000000:R>"));
        assertEquals(1, occurrences(rendered, "Large history test"));
        assertFalse(rendered.contains("Page 1 of"));
        assertFalse(rendered.contains("Players: 50000 total"));
    }

    @Test
    void filtersActivityBeforePagination() {
        long capturedAtMillis = 1_800_000_000_000L;
        DiscordActivityMessageBuilder.Settings settings = new DiscordActivityMessageBuilder.Settings(
                30,
                false,
                "last seen",
                "Filtered history",
                ValidatedConfiguration.ActivityFilter.INACTIVE
        );

        List<DiscordActivityMessageBuilder.PlayerActivity> players = new ArrayList<>();
        for (int index = 0; index < 500; index++) {
            players.add(new DiscordActivityMessageBuilder.PlayerActivity(
                    "ActivePlayer" + index,
                    capturedAtMillis
            ));
        }
        players.add(new DiscordActivityMessageBuilder.PlayerActivity(
                "InactivePlayer",
                capturedAtMillis - 31L * 24L * 60L * 60L * 1000L
        ));

        List<String> chunks = DiscordActivityMessageBuilder.build(settings, players, capturedAtMillis);

        assertEquals(1, chunks.size());
        String rendered = String.join("\n", chunks);
        assertFalse(rendered.contains("ActivePlayer"));
        assertTrue(rendered.contains("InactivePlayer (inactive)"));
    }

    @Test
    void addsPageNumbersRepeatedHeadersAndAccurateSummary() {
        long capturedAtMillis = 1_800_000_000_000L;
        DiscordActivityMessageBuilder.Settings settings = new DiscordActivityMessageBuilder.Settings(
                30,
                false,
                "last seen",
                "**Multi-page status**",
                ValidatedConfiguration.ActivityFilter.ALL,
                true,
                true,
                true
        );

        List<DiscordActivityMessageBuilder.PlayerActivity> players = new ArrayList<>();
        for (int index = 0; index < 150; index++) {
            players.add(new DiscordActivityMessageBuilder.PlayerActivity(
                    "ActivePlayer" + String.format("%03d", index),
                    capturedAtMillis
            ));
        }
        for (int index = 0; index < 150; index++) {
            players.add(new DiscordActivityMessageBuilder.PlayerActivity(
                    "InactivePlayer" + String.format("%03d", index),
                    capturedAtMillis - 31L * 24L * 60L * 60L * 1000L
            ));
        }

        List<String> chunks = DiscordActivityMessageBuilder.build(settings, players, capturedAtMillis);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= DiscordActivityMessageBuilder.DISCORD_CHUNK_MAX));
        for (int index = 0; index < chunks.size(); index++) {
            String chunk = chunks.get(index);
            assertTrue(chunk.contains("**Multi-page status**"));
            assertTrue(chunk.contains("Page " + (index + 1) + " of " + chunks.size()));
            assertTrue(chunk.contains("Players: 300 total — 150 active, 150 inactive"));
        }
    }

    @Test
    void summaryCountsOnlyPlayersRemainingAfterActivityFilter() {
        long capturedAtMillis = 1_800_000_000_000L;
        DiscordActivityMessageBuilder.Settings settings = new DiscordActivityMessageBuilder.Settings(
                30,
                false,
                "last seen",
                "Filtered summary",
                ValidatedConfiguration.ActivityFilter.INACTIVE,
                false,
                false,
                true
        );

        List<DiscordActivityMessageBuilder.PlayerActivity> players = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            players.add(new DiscordActivityMessageBuilder.PlayerActivity(
                    "ActivePlayer" + index,
                    capturedAtMillis
            ));
        }
        for (int index = 0; index < 3; index++) {
            players.add(new DiscordActivityMessageBuilder.PlayerActivity(
                    "InactivePlayer" + index,
                    capturedAtMillis - 31L * 24L * 60L * 60L * 1000L
            ));
        }

        String rendered = String.join(
                "\n",
                DiscordActivityMessageBuilder.build(settings, players, capturedAtMillis)
        );

        assertTrue(rendered.contains("Players: 3 total — 0 active, 3 inactive"));
        assertFalse(rendered.contains("ActivePlayer"));
        assertTrue(rendered.contains("InactivePlayer0 (inactive)"));
    }

    @Test
    void presentationKeepsEveryPageWithinLimitWithVeryLongConfiguredHeader() {
        long capturedAtMillis = 1_800_000_000_000L;
        DiscordActivityMessageBuilder.Settings settings = new DiscordActivityMessageBuilder.Settings(
                30,
                false,
                "last seen",
                "H".repeat(3_000),
                ValidatedConfiguration.ActivityFilter.ALL,
                true,
                true,
                true
        );

        List<DiscordActivityMessageBuilder.PlayerActivity> players = new ArrayList<>();
        for (int index = 0; index < 400; index++) {
            players.add(new DiscordActivityMessageBuilder.PlayerActivity(
                    "Player" + index,
                    capturedAtMillis
            ));
        }

        List<String> chunks = DiscordActivityMessageBuilder.build(settings, players, capturedAtMillis);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= DiscordActivityMessageBuilder.DISCORD_CHUNK_MAX));
        assertTrue(chunks.getFirst().startsWith("H".repeat(253) + "..."));
    }

    private static int occurrences(String value, String target) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(target, index)) >= 0) {
            count++;
            index += target.length();
        }
        return count;
    }
}

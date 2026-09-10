package com.pinnaclesmp.lastseendiscord;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
    }
}

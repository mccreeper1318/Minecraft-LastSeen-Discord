package com.pinnaclesmp.lastseendiscord;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class DiscordActivityMessageBuilder {
    static final int DISCORD_CHUNK_MAX = 1900;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withZone(ZoneId.systemDefault());

    private DiscordActivityMessageBuilder() {
    }

    static List<String> build(Settings settings, List<PlayerActivity> players, long capturedAtMillis) {
        long activeThreshold = capturedAtMillis
                - (settings.inactiveAfterDays() * 24L * 60L * 60L * 1000L);

        List<PlayerStatus> statuses = new ArrayList<>(players.size());
        for (PlayerActivity player : players) {
            statuses.add(new PlayerStatus(
                    player.name(),
                    player.activityTime(),
                    player.activityTime() >= activeThreshold
            ));
        }
        statuses.sort(Comparator.comparing(PlayerStatus::name, String.CASE_INSENSITIVE_ORDER));

        List<String> lines = new ArrayList<>();
        if (!settings.header().isEmpty()) {
            lines.add(settings.header());
            lines.add("");
        }

        int inactiveAfterDays = settings.inactiveAfterDays();
        lines.add("Active threshold: past " + inactiveAfterDays
                + (inactiveAfterDays == 1 ? " day" : " days"));
        lines.add("Timestamp source: " + settings.timestampSourceDisplayName());
        lines.add("");

        if (statuses.isEmpty()) {
            lines.add("No players with recorded activity history were found.");
        } else {
            for (PlayerStatus status : statuses) {
                StringBuilder playerLine = new StringBuilder();
                playerLine.append("- ")
                        .append(escapeDiscord(status.name()))
                        .append(" (")
                        .append(status.active() ? "active" : "inactive")
                        .append(")");
                if (settings.includeActivityDate()) {
                    playerLine.append(" — ")
                            .append(DATE_FORMATTER.format(Instant.ofEpochMilli(status.activityTime())));
                }
                lines.add(playerLine.toString());
            }
        }

        lines.add("");
        lines.add("Updated: <t:" + (capturedAtMillis / 1000L) + ":R>");
        return splitIntoChunks(lines);
    }

    private static List<String> splitIntoChunks(List<String> lines) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : lines) {
            if (line.length() <= DISCORD_CHUNK_MAX) {
                appendLineToChunks(chunks, current, line);
                continue;
            }

            for (int start = 0; start < line.length(); start += DISCORD_CHUNK_MAX) {
                int end = Math.min(start + DISCORD_CHUNK_MAX, line.length());
                appendLineToChunks(chunks, current, line.substring(start, end));
            }
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        if (chunks.isEmpty()) {
            chunks.add("");
        }
        return List.copyOf(chunks);
    }

    private static void appendLineToChunks(List<String> chunks, StringBuilder current, String line) {
        String candidate = current.isEmpty() ? line : current + "\n" + line;
        if (candidate.length() <= DISCORD_CHUNK_MAX) {
            current.setLength(0);
            current.append(candidate);
            return;
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString());
            current.setLength(0);
        }
        current.append(line);
    }

    private static String escapeDiscord(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("~", "\\~")
                .replace("`", "\\`")
                .replace("|", "\\|")
                .replace(">", "\\>")
                .replace("@", "@\u200B");
    }

    record Settings(
            int inactiveAfterDays,
            boolean includeActivityDate,
            String timestampSourceDisplayName,
            String header
    ) {
        Settings {
            inactiveAfterDays = Math.max(1, inactiveAfterDays);
            timestampSourceDisplayName = timestampSourceDisplayName == null
                    ? "last seen"
                    : timestampSourceDisplayName;
            header = header == null ? "" : header.trim();
        }
    }

    record PlayerActivity(String name, long activityTime) {
    }

    private record PlayerStatus(String name, long activityTime, boolean active) {
    }
}

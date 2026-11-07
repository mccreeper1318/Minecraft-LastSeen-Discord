package com.pinnaclesmp.lastseendiscord;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class DiscordActivityMessageBuilder {
    static final int DISCORD_CHUNK_MAX = 1900;
    private static final int COMPACT_HEADER_MAX = 256;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withZone(ZoneId.systemDefault());

    private DiscordActivityMessageBuilder() {
    }

    static List<String> build(Settings settings, List<PlayerActivity> players, long capturedAtMillis) {
        long activeThreshold = capturedAtMillis
                - (settings.inactiveAfterDays() * 24L * 60L * 60L * 1000L);

        List<PlayerStatus> statuses = new ArrayList<>(players.size());
        for (PlayerActivity player : players) {
            boolean active = player.activityTime() >= activeThreshold;
            if (!settings.activityFilter().includes(active)) {
                continue;
            }
            statuses.add(new PlayerStatus(
                    player.name(),
                    player.activityTime(),
                    active
            ));
        }
        statuses.sort(Comparator.comparing(PlayerStatus::name, String.CASE_INSENSITIVE_ORDER));

        if (!settings.presentationEnabled()) {
            return buildLegacy(settings, statuses, capturedAtMillis);
        }
        return buildPresented(settings, statuses, capturedAtMillis);
    }

    private static List<String> buildLegacy(Settings settings, List<PlayerStatus> statuses, long capturedAtMillis) {
        List<String> lines = new ArrayList<>();
        if (!settings.header().isEmpty()) {
            lines.add(settings.header());
            lines.add("");
        }

        appendActivityMetadata(lines, settings);
        appendPlayerLines(lines, settings, statuses);
        appendUpdatedLine(lines, capturedAtMillis);
        return splitIntoChunks(lines);
    }

    private static List<String> buildPresented(Settings settings, List<PlayerStatus> statuses, long capturedAtMillis) {
        int activeCount = 0;
        for (PlayerStatus status : statuses) {
            if (status.active()) {
                activeCount++;
            }
        }
        int inactiveCount = statuses.size() - activeCount;

        List<String> bodyLines = new ArrayList<>();
        appendActivityMetadata(bodyLines, settings);
        appendPlayerLines(bodyLines, settings, statuses);
        appendUpdatedLine(bodyLines, capturedAtMillis);

        int totalPageGuess = 1;
        List<String> pages = List.of();
        for (int attempt = 0; attempt < 12; attempt++) {
            pages = paginatePresented(
                    settings,
                    bodyLines,
                    statuses.size(),
                    activeCount,
                    inactiveCount,
                    totalPageGuess
            );
            if (!settings.showPageNumbers() || pages.size() == totalPageGuess) {
                return pages;
            }
            totalPageGuess = pages.size();
        }

        return paginatePresented(
                settings,
                bodyLines,
                statuses.size(),
                activeCount,
                inactiveCount,
                Math.max(1, pages.size())
        );
    }

    private static List<String> paginatePresented(
            Settings settings,
            List<String> bodyLines,
            int totalCount,
            int activeCount,
            int inactiveCount,
            int totalPageGuess
    ) {
        List<String> pages = new ArrayList<>();
        int bodyIndex = 0;
        int pageNumber = 1;

        while (bodyIndex < bodyLines.size() || pages.isEmpty()) {
            StringBuilder current = new StringBuilder();
            for (String prefixLine : pagePrefix(
                    settings,
                    pageNumber,
                    totalPageGuess,
                    totalCount,
                    activeCount,
                    inactiveCount
            )) {
                if (!appendLine(current, prefixLine)) {
                    throw new IllegalStateException("Discord page presentation prefix exceeded the safe message limit.");
                }
            }

            int startingBodyIndex = bodyIndex;
            while (bodyIndex < bodyLines.size() && appendLine(current, bodyLines.get(bodyIndex))) {
                bodyIndex++;
            }

            if (bodyIndex == startingBodyIndex && bodyIndex < bodyLines.size()) {
                String line = bodyLines.get(bodyIndex);
                int available = DISCORD_CHUNK_MAX - current.length() - (current.isEmpty() ? 0 : 1);
                if (available <= 0) {
                    pages.add(current.toString());
                    pageNumber++;
                    continue;
                }

                current.append(current.isEmpty() ? "" : "\n");
                current.append(line, 0, Math.min(line.length(), available));
                bodyIndex++;
            }

            pages.add(current.toString());
            pageNumber++;
        }

        return List.copyOf(pages);
    }

    private static List<String> pagePrefix(
            Settings settings,
            int pageNumber,
            int totalPages,
            int totalCount,
            int activeCount,
            int inactiveCount
    ) {
        List<String> lines = new ArrayList<>();
        if (!settings.header().isEmpty() && (pageNumber == 1 || settings.repeatHeader())) {
            lines.add(compactHeader(settings.header()));
        }
        if (settings.showPageNumbers()) {
            lines.add("Page " + pageNumber + " of " + totalPages);
        }
        if (settings.showSummary()) {
            lines.add("Players: " + totalCount + " total — "
                    + activeCount + " active, " + inactiveCount + " inactive");
        }
        if (!lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    private static String compactHeader(String header) {
        if (header.length() <= COMPACT_HEADER_MAX) {
            return header;
        }
        return header.substring(0, COMPACT_HEADER_MAX - 3) + "...";
    }

    private static void appendActivityMetadata(List<String> lines, Settings settings) {
        int inactiveAfterDays = settings.inactiveAfterDays();
        lines.add("Active threshold: past " + inactiveAfterDays
                + (inactiveAfterDays == 1 ? " day" : " days"));
        lines.add("Timestamp source: " + settings.timestampSourceDisplayName());
        lines.add("");
    }

    private static void appendPlayerLines(List<String> lines, Settings settings, List<PlayerStatus> statuses) {
        if (statuses.isEmpty()) {
            lines.add("No players with recorded activity history were found.");
            return;
        }

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

    private static void appendUpdatedLine(List<String> lines, long capturedAtMillis) {
        lines.add("");
        lines.add("Updated: <t:" + (capturedAtMillis / 1000L) + ":R>");
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
        if (appendLine(current, line)) {
            return;
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString());
            current.setLength(0);
        }
        current.append(line);
    }

    private static boolean appendLine(StringBuilder current, String line) {
        int separatorLength = current.isEmpty() ? 0 : 1;
        if (current.length() + separatorLength + line.length() > DISCORD_CHUNK_MAX) {
            return false;
        }
        if (!current.isEmpty()) {
            current.append('\n');
        }
        current.append(line);
        return true;
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
            String header,
            ValidatedConfiguration.ActivityFilter activityFilter,
            boolean showPageNumbers,
            boolean repeatHeader,
            boolean showSummary
    ) {
        Settings(int inactiveAfterDays, boolean includeActivityDate, String timestampSourceDisplayName, String header) {
            this(
                    inactiveAfterDays,
                    includeActivityDate,
                    timestampSourceDisplayName,
                    header,
                    ValidatedConfiguration.ActivityFilter.ALL,
                    false,
                    false,
                    false
            );
        }

        Settings(
                int inactiveAfterDays,
                boolean includeActivityDate,
                String timestampSourceDisplayName,
                String header,
                ValidatedConfiguration.ActivityFilter activityFilter
        ) {
            this(
                    inactiveAfterDays,
                    includeActivityDate,
                    timestampSourceDisplayName,
                    header,
                    activityFilter,
                    false,
                    false,
                    false
            );
        }

        Settings {
            inactiveAfterDays = Math.max(1, inactiveAfterDays);
            timestampSourceDisplayName = timestampSourceDisplayName == null
                    ? "last seen"
                    : timestampSourceDisplayName;
            header = header == null ? "" : header.trim();
            activityFilter = activityFilter == null
                    ? ValidatedConfiguration.ActivityFilter.ALL
                    : activityFilter;
        }

        boolean presentationEnabled() {
            return showPageNumbers || repeatHeader || showSummary;
        }
    }

    record PlayerActivity(String name, long activityTime) {
    }

    private record PlayerStatus(String name, long activityTime, boolean active) {
    }
}

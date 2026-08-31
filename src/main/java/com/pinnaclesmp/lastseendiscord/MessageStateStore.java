package com.pinnaclesmp.lastseendiscord;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class MessageStateStore {
    private static final int STATE_VERSION = 2;
    private static final Gson GSON = new Gson();

    private final Path stateFile;

    MessageStateStore(Path stateFile) {
        this.stateFile = stateFile;
    }

    State load() throws IOException {
        if (!Files.exists(stateFile)) {
            return new State(List.of(), false);
        }

        try {
            StateDocument document = GSON.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8), StateDocument.class);
            if (document == null
                    || (document.version != 1 && document.version != STATE_VERSION)
                    || document.messageIds == null) {
                throw new IOException("The Discord message state file has an unsupported format.");
            }
            List<String> sanitized = sanitize(document.messageIds);
            if (sanitized.size() != document.messageIds.size()) {
                throw new IOException("The Discord message state file contains invalid or duplicate IDs.");
            }
            return new State(List.copyOf(sanitized), document.version >= 2 && document.createOutcomeUnknown);
        } catch (JsonParseException ex) {
            throw new IOException("The Discord message state file is not valid JSON.", ex);
        }
    }

    void save(List<String> messageIds, boolean createOutcomeUnknown) throws IOException {
        List<String> safeIds = sanitize(messageIds);
        if (safeIds.size() != messageIds.size()) {
            throw new IOException("Refusing to persist invalid Discord message IDs.");
        }

        Path directory = stateFile.getParent();
        Files.createDirectories(directory);
        Path temporaryFile = directory.resolve(stateFile.getFileName() + ".tmp");
        String json = GSON.toJson(new StateDocument(STATE_VERSION, safeIds, createOutcomeUnknown))
                + System.lineSeparator();
        Files.writeString(
                temporaryFile,
                json,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
        try (FileChannel channel = FileChannel.open(temporaryFile, StandardOpenOption.WRITE)) {
            channel.force(true);
        }

        Files.move(
                temporaryFile,
                stateFile,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
        );
        try (FileChannel directoryChannel = FileChannel.open(directory, StandardOpenOption.READ)) {
            directoryChannel.force(true);
        }
    }

    static List<String> sanitize(List<String> messageIds) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String messageId : messageIds) {
            if (messageId == null) {
                continue;
            }
            String trimmed = messageId.trim();
            if (WebhookEndpoint.isValidMessageId(trimmed)) {
                unique.add(trimmed);
            }
        }
        return new ArrayList<>(unique);
    }

    static List<String> selectLegacyIds(List<String> configuredIds, String configuredId) {
        List<String> sanitized = sanitize(configuredIds);
        if (!sanitized.isEmpty()) {
            return sanitized;
        }
        return sanitize(List.of(configuredId == null ? "" : configuredId));
    }

    record State(List<String> messageIds, boolean createOutcomeUnknown) {
    }

    private record StateDocument(int version, List<String> messageIds, boolean createOutcomeUnknown) {
    }
}

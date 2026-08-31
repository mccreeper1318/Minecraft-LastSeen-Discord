package com.pinnaclesmp.lastseendiscord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageStateStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndLoadsStateWithoutLeavingTemporaryFiles() throws Exception {
        Path stateFile = temporaryDirectory.resolve("data/message-state.json");
        MessageStateStore store = new MessageStateStore(stateFile);
        List<String> ids = List.of("111111111111111111", "222222222222222222");

        store.save(ids, false);

        assertEquals(ids, store.load().messageIds());
        assertFalse(store.load().createOutcomeUnknown());
        assertFalse(Files.exists(stateFile.resolveSibling("message-state.json.tmp")));
    }

    @Test
    void refusesToPersistMalformedOrDuplicateIds() {
        MessageStateStore store = new MessageStateStore(temporaryDirectory.resolve("message-state.json"));
        assertThrows(IOException.class, () -> store.save(List.of("not-an-id"), false));
        assertThrows(IOException.class, () -> store.save(List.of(
                "111111111111111111",
                "111111111111111111"
        ), false));
    }

    @Test
    void rejectsMalformedStateJson() throws Exception {
        Path stateFile = temporaryDirectory.resolve("message-state.json");
        Files.writeString(stateFile, "{not-json", StandardCharsets.UTF_8);
        MessageStateStore store = new MessageStateStore(stateFile);

        assertThrows(IOException.class, store::load);
    }

    @Test
    void persistsAmbiguousCreateBlockAcrossReloads() throws Exception {
        Path stateFile = temporaryDirectory.resolve("message-state.json");
        MessageStateStore store = new MessageStateStore(stateFile);

        store.save(List.of("111111111111111111"), true);

        MessageStateStore.State state = store.load();
        assertEquals(List.of("111111111111111111"), state.messageIds());
        assertEquals(true, state.createOutcomeUnknown());
    }

    @Test
    void fallsBackToSingleLegacyIdWhenConfiguredListSanitizesToEmpty() {
        assertEquals(
                List.of("111111111111111111"),
                MessageStateStore.selectLegacyIds(
                        List.of("", "   ", "not-an-id"),
                        "111111111111111111"
                )
        );
    }

    @Test
    void prefersValidConfiguredListOverSingleLegacyId() {
        assertEquals(
                List.of("222222222222222222"),
                MessageStateStore.selectLegacyIds(
                        List.of("222222222222222222"),
                        "111111111111111111"
                )
        );
    }
}

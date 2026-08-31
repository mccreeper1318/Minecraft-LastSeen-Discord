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

        store.save(ids);

        assertEquals(ids, store.load());
        assertFalse(Files.exists(stateFile.resolveSibling("message-state.json.tmp")));
    }

    @Test
    void refusesToPersistMalformedOrDuplicateIds() {
        MessageStateStore store = new MessageStateStore(temporaryDirectory.resolve("message-state.json"));
        assertThrows(IOException.class, () -> store.save(List.of("not-an-id")));
        assertThrows(IOException.class, () -> store.save(List.of(
                "111111111111111111",
                "111111111111111111"
        )));
    }

    @Test
    void rejectsMalformedStateJson() throws Exception {
        Path stateFile = temporaryDirectory.resolve("message-state.json");
        Files.writeString(stateFile, "{not-json", StandardCharsets.UTF_8);
        MessageStateStore store = new MessageStateStore(stateFile);

        assertThrows(IOException.class, store::load);
    }
}

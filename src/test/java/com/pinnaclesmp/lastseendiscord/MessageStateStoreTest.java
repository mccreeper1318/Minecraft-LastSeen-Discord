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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageStateStoreTest {
    private static final String WEBHOOK_IDENTITY = "discord-webhook:123456789012345678";

    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndLoadsStateWithoutLeavingTemporaryFiles() throws Exception {
        Path stateFile = temporaryDirectory.resolve("data/message-state.json");
        MessageStateStore store = new MessageStateStore(stateFile);
        List<String> ids = List.of("111111111111111111", "222222222222222222");

        store.save(ids, false, WEBHOOK_IDENTITY);

        assertEquals(ids, store.load().messageIds());
        assertFalse(store.load().createOutcomeUnknown());
        assertEquals(WEBHOOK_IDENTITY, store.load().webhookIdentity());
        assertFalse(Files.exists(stateFile.resolveSibling("message-state.json.tmp")));
    }

    @Test
    void refusesToPersistMalformedOrDuplicateIds() {
        MessageStateStore store = new MessageStateStore(temporaryDirectory.resolve("message-state.json"));
        assertThrows(IOException.class, () -> store.save(List.of("not-an-id"), false, WEBHOOK_IDENTITY));
        assertThrows(IOException.class, () -> store.save(List.of(
                "111111111111111111",
                "111111111111111111"
        ), false, WEBHOOK_IDENTITY));
    }

    @Test
    void refusesToPersistTrackedStateWithoutWebhookIdentity() {
        MessageStateStore store = new MessageStateStore(temporaryDirectory.resolve("message-state.json"));

        assertThrows(IOException.class, () -> store.save(List.of("111111111111111111"), false, null));
        assertThrows(IOException.class, () -> store.save(List.of(), true, null));
    }

    @Test
    void failsWhenTheStateDirectoryCannotBeCreated() throws Exception {
        Path occupiedDirectory = temporaryDirectory.resolve("occupied");
        Files.writeString(occupiedDirectory, "not a directory", StandardCharsets.UTF_8);
        MessageStateStore store = new MessageStateStore(occupiedDirectory.resolve("message-state.json"));

        assertThrows(IOException.class, () -> store.save(
                List.of("111111111111111111"),
                false,
                WEBHOOK_IDENTITY
        ));
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

        store.save(List.of("111111111111111111"), true, WEBHOOK_IDENTITY);

        MessageStateStore.State state = store.load();
        assertEquals(List.of("111111111111111111"), state.messageIds());
        assertTrue(state.createOutcomeUnknown());
        assertEquals(WEBHOOK_IDENTITY, state.webhookIdentity());
    }

    @Test
    void legacyStateLoadsAsUnboundSoItCannotBeSilentlyReused() throws Exception {
        Path stateFile = temporaryDirectory.resolve("message-state.json");
        Files.writeString(
                stateFile,
                "{\"version\":2,\"messageIds\":[\"111111111111111111\"],\"createOutcomeUnknown\":false}\n",
                StandardCharsets.UTF_8
        );
        MessageStateStore store = new MessageStateStore(stateFile);

        MessageStateStore.State state = store.load();

        assertEquals(List.of("111111111111111111"), state.messageIds());
        assertNull(state.webhookIdentity());
    }

    @Test
    void persistedIdentityNeverContainsWebhookToken() throws Exception {
        Path stateFile = temporaryDirectory.resolve("message-state.json");
        MessageStateStore store = new MessageStateStore(stateFile);
        String token = "very_secret_webhook_token";
        WebhookEndpoint endpoint = WebhookEndpoint.parse(
                "https://discord.com/api/webhooks/123456789012345678/" + token
        );

        store.save(List.of("111111111111111111"), false, endpoint.stateIdentity());

        String json = Files.readString(stateFile, StandardCharsets.UTF_8);
        assertFalse(json.contains(token));
        assertTrue(json.contains("discord-webhook:123456789012345678"));
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

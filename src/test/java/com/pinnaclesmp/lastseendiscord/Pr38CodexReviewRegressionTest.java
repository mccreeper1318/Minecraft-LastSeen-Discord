package com.pinnaclesmp.lastseendiscord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Pr38CodexReviewRegressionTest {
    private static final String WEBHOOK_ID = "123456789012345678";
    private static final String MESSAGE_ID = "111111111111111111";
    private static final String THREAD_A = "222222222222222222";
    private static final String THREAD_B = "333333333333333333";

    @TempDir
    Path temporaryDirectory;

    @Test
    void versionTwoStateBindsToConfiguredWebhookWithoutDroppingTrackedIds() throws Exception {
        Path stateFile = temporaryDirectory.resolve("message-state.json");
        Files.writeString(
                stateFile,
                "{\"version\":2,\"messageIds\":[\"" + MESSAGE_ID
                        + "\"],\"createOutcomeUnknown\":true}\n",
                StandardCharsets.UTF_8
        );
        MessageStateStore store = new MessageStateStore(stateFile);
        MessageStateStore.State loaded = store.load();

        assertTrue(loaded.identityBindingRequired());
        assertEquals(List.of(MESSAGE_ID), loaded.messageIds());
        assertTrue(loaded.createOutcomeUnknown());

        WebhookStateManager manager = new WebhookStateManager(store, loaded);
        String configuredIdentity = "discord-webhook:" + WEBHOOK_ID;

        assertFalse(manager.advanceConfiguration(configuredIdentity, true));

        WebhookStateManager.Snapshot current = manager.snapshot();
        assertEquals(configuredIdentity, current.webhookIdentity());
        assertEquals(List.of(MESSAGE_ID), current.messageIds());
        assertTrue(current.createOutcomeUnknown());

        MessageStateStore.State migrated = store.load();
        assertEquals(configuredIdentity, migrated.webhookIdentity());
        assertEquals(List.of(MESSAGE_ID), migrated.messageIds());
        assertTrue(migrated.createOutcomeUnknown());
        assertFalse(migrated.identityBindingRequired());
    }

    @Test
    void unconfiguredReloadRetainsBoundMessageStateUntilWebhookReturns() throws Exception {
        Path stateFile = temporaryDirectory.resolve("message-state.json");
        MessageStateStore store = new MessageStateStore(stateFile);
        String identity = "discord-webhook:" + WEBHOOK_ID;
        store.save(List.of(MESSAGE_ID), false, identity);

        WebhookStateManager manager = new WebhookStateManager(store, store.load());
        assertFalse(manager.advanceConfiguration(identity, true));

        assertFalse(manager.advanceConfiguration(null, true));
        assertEquals(identity, manager.snapshot().webhookIdentity());
        assertEquals(List.of(MESSAGE_ID), manager.snapshot().messageIds());

        assertFalse(manager.advanceConfiguration(identity, true));
        assertEquals(identity, manager.snapshot().webhookIdentity());
        assertEquals(List.of(MESSAGE_ID), manager.snapshot().messageIds());
        assertEquals(List.of(MESSAGE_ID), store.load().messageIds());
    }

    @Test
    void threadIdChangesStateIdentityWhileTokenRotationDoesNot() throws Exception {
        WebhookEndpoint first = WebhookEndpoint.parse(
                "https://discord.com/api/webhooks/" + WEBHOOK_ID + "/first_token?thread_id=" + THREAD_A
        );
        WebhookEndpoint rotatedToken = WebhookEndpoint.parse(
                "https://discord.com/api/webhooks/" + WEBHOOK_ID + "/second_token?thread_id=" + THREAD_A
        );
        WebhookEndpoint differentThread = WebhookEndpoint.parse(
                "https://discord.com/api/webhooks/" + WEBHOOK_ID + "/second_token?thread_id=" + THREAD_B
        );
        WebhookEndpoint noThread = WebhookEndpoint.parse(
                "https://discord.com/api/webhooks/" + WEBHOOK_ID + "/second_token"
        );

        assertEquals("discord-webhook:" + WEBHOOK_ID + ":thread:" + THREAD_A, first.stateIdentity());
        assertEquals(first.stateIdentity(), rotatedToken.stateIdentity());
        assertNotEquals(first.stateIdentity(), differentThread.stateIdentity());
        assertNotEquals(first.stateIdentity(), noThread.stateIdentity());
        assertFalse(first.stateIdentity().contains("first_token"));
        assertFalse(rotatedToken.stateIdentity().contains("second_token"));
        assertTrue(WebhookEndpoint.isValidStateIdentity(first.stateIdentity()));
    }

    @Test
    void changingThreadClearsIdsForASeparateMessageLifecycle() throws Exception {
        Path stateFile = temporaryDirectory.resolve("message-state.json");
        MessageStateStore store = new MessageStateStore(stateFile);
        String threadAIdentity = "discord-webhook:" + WEBHOOK_ID + ":thread:" + THREAD_A;
        String threadBIdentity = "discord-webhook:" + WEBHOOK_ID + ":thread:" + THREAD_B;
        store.save(List.of(MESSAGE_ID), false, threadAIdentity);

        WebhookStateManager manager = new WebhookStateManager(store, store.load());

        assertTrue(manager.advanceConfiguration(threadBIdentity, true));
        assertEquals(threadBIdentity, manager.snapshot().webhookIdentity());
        assertEquals(List.of(), manager.snapshot().messageIds());
        assertEquals(threadBIdentity, store.load().webhookIdentity());
        assertEquals(List.of(), store.load().messageIds());
    }

    @Test
    void rejectsMalformedOrDuplicateThreadSelectors() {
        assertThrows(SyncException.class, () -> WebhookEndpoint.parse(
                "https://discord.com/api/webhooks/" + WEBHOOK_ID + "/token?thread_id=not-a-snowflake"
        ));
        assertThrows(SyncException.class, () -> WebhookEndpoint.parse(
                "https://discord.com/api/webhooks/" + WEBHOOK_ID + "/token?thread_id=" + THREAD_A
                        + "&thread_id=" + THREAD_B
        ));
    }
}

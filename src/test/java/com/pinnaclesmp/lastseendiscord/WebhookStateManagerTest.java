package com.pinnaclesmp.lastseendiscord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookStateManagerTest {
    private static final String OLD_IDENTITY = "discord-webhook:111111111111111111";
    private static final String NEW_IDENTITY = "discord-webhook:222222222222222222";

    @TempDir
    Path temporaryDirectory;

    @Test
    void reloadDuringCreationCannotCommitOldWebhookResult() throws Exception {
        MessageStateStore store = new MessageStateStore(temporaryDirectory.resolve("message-state.json"));
        store.save(List.of(), false, OLD_IDENTITY);
        WebhookStateManager manager = new WebhookStateManager(store, store.load());
        manager.advanceConfiguration(OLD_IDENTITY, true);

        WebhookStateManager.Snapshot oldSnapshot = manager.snapshot();
        DiscordMessageSynchronizer.MessageState oldState = manager.bind(
                oldSnapshot.generation(),
                oldSnapshot.webhookIdentity()
        );
        oldState.beginCreate(List.of());
        assertTrue(manager.snapshot().createOutcomeUnknown());
        assertTrue(manager.snapshot().createInProgress());

        assertTrue(manager.advanceConfiguration(NEW_IDENTITY, true));

        assertThrows(
                StaleConfigurationException.class,
                () -> oldState.completeCreate(List.of("333333333333333333"))
        );

        WebhookStateManager.Snapshot current = manager.snapshot();
        assertEquals(NEW_IDENTITY, current.webhookIdentity());
        assertEquals(List.of(), current.messageIds());
        assertFalse(current.createOutcomeUnknown());
        assertFalse(current.createInProgress());

        MessageStateStore.State persisted = store.load();
        assertEquals(NEW_IDENTITY, persisted.webhookIdentity());
        assertEquals(List.of(), persisted.messageIds());
        assertFalse(persisted.createOutcomeUnknown());
    }

    @Test
    void reloadDuringUpdateCannotRestoreOldWebhookMessageIds() throws Exception {
        MessageStateStore store = new MessageStateStore(temporaryDirectory.resolve("message-state.json"));
        store.save(List.of("333333333333333333"), false, OLD_IDENTITY);
        WebhookStateManager manager = new WebhookStateManager(store, store.load());
        manager.advanceConfiguration(OLD_IDENTITY, true);

        WebhookStateManager.Snapshot oldSnapshot = manager.snapshot();

        assertTrue(manager.advanceConfiguration(NEW_IDENTITY, true));

        assertThrows(
                StaleConfigurationException.class,
                () -> manager.commitSyncResult(
                        oldSnapshot.generation(),
                        oldSnapshot.webhookIdentity(),
                        List.of("333333333333333333")
                )
        );

        WebhookStateManager.Snapshot current = manager.snapshot();
        assertEquals(NEW_IDENTITY, current.webhookIdentity());
        assertEquals(List.of(), current.messageIds());
        assertFalse(current.createOutcomeUnknown());

        MessageStateStore.State persisted = store.load();
        assertEquals(NEW_IDENTITY, persisted.webhookIdentity());
        assertEquals(List.of(), persisted.messageIds());
    }

    @Test
    void sameWebhookReloadStillInvalidatesOldGeneration() throws Exception {
        MessageStateStore store = new MessageStateStore(temporaryDirectory.resolve("message-state.json"));
        store.save(List.of("333333333333333333"), false, OLD_IDENTITY);
        WebhookStateManager manager = new WebhookStateManager(store, store.load());
        manager.advanceConfiguration(OLD_IDENTITY, true);
        WebhookStateManager.Snapshot oldSnapshot = manager.snapshot();

        assertFalse(manager.advanceConfiguration(OLD_IDENTITY, true));

        assertThrows(
                StaleConfigurationException.class,
                () -> manager.commitSyncResult(
                        oldSnapshot.generation(),
                        oldSnapshot.webhookIdentity(),
                        oldSnapshot.messageIds()
                )
        );
        assertEquals(List.of("333333333333333333"), manager.snapshot().messageIds());
    }
}

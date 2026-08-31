package com.pinnaclesmp.lastseendiscord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class DiscordMessageSynchronizer {
    private final MessageClient client;
    private final MessageState state;

    DiscordMessageSynchronizer(MessageClient client, MessageState state) {
        this.client = client;
        this.state = state;
    }

    List<String> synchronize(WebhookEndpoint endpoint, List<String> chunks, List<String> initialMessageIds)
            throws IOException, InterruptedException {
        List<String> messageIds = new ArrayList<>(initialMessageIds);

        for (int index = 0; index < chunks.size(); index++) {
            String content = chunks.get(index);
            if (index < messageIds.size()) {
                EditResult result = client.edit(endpoint, messageIds.get(index), content);
                if (result == EditResult.MISSING) {
                    String replacementId = createSafely(endpoint, content, messageIds);
                    messageIds.set(index, replacementId);
                    completeCreate(messageIds);
                }
                continue;
            }

            String createdId = createSafely(endpoint, content, messageIds);
            messageIds.add(createdId);
            completeCreate(messageIds);
        }

        while (messageIds.size() > chunks.size()) {
            int lastIndex = messageIds.size() - 1;
            client.delete(endpoint, messageIds.get(lastIndex));
            messageIds.remove(lastIndex);
            persist(messageIds);
        }

        return List.copyOf(messageIds);
    }

    private void persist(List<String> messageIds) throws IOException {
        List<String> snapshot = List.copyOf(messageIds);
        try {
            state.persist(snapshot);
        } catch (IOException persistenceFailure) {
            try {
                state.blockCreate(snapshot);
            } catch (IOException blockFailure) {
                persistenceFailure.addSuppressed(blockFailure);
            }
            throw new SyncException(
                    "Discord message state could not be saved. Automatic message creation is paused until "
                            + "the state storage problem is fixed and /lsd recover-create confirm is run.",
                    persistenceFailure
            );
        }
    }

    private String createSafely(WebhookEndpoint endpoint, String content, List<String> knownMessageIds)
            throws IOException, InterruptedException {
        if (state.isCreateBlocked()) {
            throw new SyncException(
                    "Automatic Discord message creation is paused after an earlier create returned an unknown "
                            + "outcome. Inspect the channel, then use /lsd recover-create confirm."
            );
        }

        state.blockCreate(List.copyOf(knownMessageIds));
        try {
            return client.create(endpoint, content);
        } catch (AmbiguousCreateException ex) {
            state.finishCreateAttempt();
            throw ex;
        } catch (RetryableSyncException ex) {
            if (!ex.deliveryMayBeAmbiguous()) {
                cancelCreateIntent(knownMessageIds, ex);
            } else {
                state.finishCreateAttempt();
            }
            throw ex;
        } catch (SyncException ex) {
            cancelCreateIntent(knownMessageIds, ex);
            throw ex;
        } catch (IOException | InterruptedException ex) {
            state.finishCreateAttempt();
            throw ex;
        }
    }

    private void cancelCreateIntent(List<String> knownMessageIds, IOException originalFailure) throws IOException {
        try {
            state.cancelCreate(List.copyOf(knownMessageIds));
        } catch (IOException cancellationFailure) {
            cancellationFailure.addSuppressed(originalFailure);
            throw new SyncException(
                    "Discord rejected the create request, but its write-ahead intent could not be cleared. "
                            + "Automatic creation remains paused until storage is fixed.",
                    cancellationFailure
            );
        }
    }

    private void completeCreate(List<String> messageIds) throws IOException {
        try {
            state.completeCreate(List.copyOf(messageIds));
        } catch (IOException ex) {
            throw new SyncException(
                    "The Discord message was created, but its ID could not be committed. Automatic message "
                            + "creation remains paused until storage is fixed and recovery is confirmed.",
                    ex
            );
        }
    }

    enum EditResult {
        UPDATED,
        MISSING
    }

    interface MessageClient {
        String create(WebhookEndpoint endpoint, String content) throws IOException, InterruptedException;

        EditResult edit(WebhookEndpoint endpoint, String messageId, String content) throws IOException, InterruptedException;

        void delete(WebhookEndpoint endpoint, String messageId) throws IOException, InterruptedException;
    }

    interface MessageState {
        boolean isCreateBlocked();

        void persist(List<String> messageIds) throws IOException;

        void blockCreate(List<String> knownMessageIds) throws IOException;

        void completeCreate(List<String> messageIds) throws IOException;

        void cancelCreate(List<String> knownMessageIds) throws IOException;

        void finishCreateAttempt();
    }
}

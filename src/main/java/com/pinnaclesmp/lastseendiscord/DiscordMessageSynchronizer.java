package com.pinnaclesmp.lastseendiscord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class DiscordMessageSynchronizer {
    private final MessageClient client;
    private final MessageStateSink stateSink;

    DiscordMessageSynchronizer(MessageClient client, MessageStateSink stateSink) {
        this.client = client;
        this.stateSink = stateSink;
    }

    List<String> synchronize(WebhookEndpoint endpoint, List<String> chunks, List<String> initialMessageIds)
            throws IOException, InterruptedException {
        List<String> messageIds = new ArrayList<>(initialMessageIds);

        for (int index = 0; index < chunks.size(); index++) {
            String content = chunks.get(index);
            if (index < messageIds.size()) {
                EditResult result = client.edit(endpoint, messageIds.get(index), content);
                if (result == EditResult.MISSING) {
                    String replacementId = client.create(endpoint, content);
                    messageIds.set(index, replacementId);
                    persist(messageIds);
                }
                continue;
            }

            String createdId = client.create(endpoint, content);
            messageIds.add(createdId);
            persist(messageIds);
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
        stateSink.persist(List.copyOf(messageIds));
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

    interface MessageStateSink {
        void persist(List<String> messageIds) throws IOException;
    }
}

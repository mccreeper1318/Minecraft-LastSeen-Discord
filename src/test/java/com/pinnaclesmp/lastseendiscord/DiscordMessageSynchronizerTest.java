package com.pinnaclesmp.lastseendiscord;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiscordMessageSynchronizerTest {
    private static final WebhookEndpoint ENDPOINT;

    static {
        try {
            ENDPOINT = WebhookEndpoint.parse(
                    "https://discord.com/api/webhooks/123456789012345678/test_token"
            );
        } catch (SyncException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @Test
    void persistsEveryCreatedIdBeforeCreatingTheNextPage() throws Exception {
        FakeClient client = new FakeClient("111111111111111111", "222222222222222222");
        FakeState state = new FakeState();
        DiscordMessageSynchronizer synchronizer = new DiscordMessageSynchronizer(client, state);

        List<String> result = synchronizer.synchronize(ENDPOINT, List.of("one", "two"), List.of());

        assertEquals(List.of("111111111111111111", "222222222222222222"), result);
        assertEquals(List.of(
                List.of("111111111111111111"),
                List.of("111111111111111111", "222222222222222222")
        ), state.savedStates);
        assertEquals(List.of("create:one", "create:two"), client.events);
    }

    @Test
    void keepsFirstCreatedIdWhenALaterPageFails() throws Exception {
        FakeClient client = new FakeClient("111111111111111111");
        client.failCreateNumber = 2;
        FakeState state = new FakeState();
        DiscordMessageSynchronizer synchronizer = new DiscordMessageSynchronizer(client, state);

        assertThrows(IOException.class, () -> synchronizer.synchronize(
                ENDPOINT,
                List.of("one", "two"),
                List.of()
        ));
        assertEquals(List.of(List.of("111111111111111111")), state.savedStates);
    }

    @Test
    void recreatesOnlyADeletedTrackedMessageAndPersistsReplacement() throws Exception {
        FakeClient client = new FakeClient("333333333333333333");
        client.missingIds.add("111111111111111111");
        FakeState state = new FakeState();
        DiscordMessageSynchronizer synchronizer = new DiscordMessageSynchronizer(client, state);

        List<String> result = synchronizer.synchronize(
                ENDPOINT,
                List.of("one", "two"),
                List.of("111111111111111111", "222222222222222222")
        );

        assertEquals(List.of("333333333333333333", "222222222222222222"), result);
        assertEquals(List.of(List.of("333333333333333333", "222222222222222222")), state.savedStates);
        assertEquals(List.of(
                "edit:111111111111111111:one",
                "create:one",
                "edit:222222222222222222:two"
        ), client.events);
    }

    @Test
    void ambiguousCreateIsDurablyBlockedAndNeverRetried() throws Exception {
        FakeClient client = new FakeClient();
        client.ambiguousCreateNumber = 1;
        FakeState state = new FakeState();
        DiscordMessageSynchronizer synchronizer = new DiscordMessageSynchronizer(client, state);

        assertThrows(AmbiguousCreateException.class, () -> synchronizer.synchronize(
                ENDPOINT,
                List.of("one"),
                List.of()
        ));
        assertEquals(true, state.createBlocked);
        assertEquals(List.of(List.of()), state.blockedStates);

        assertThrows(SyncException.class, () -> synchronizer.synchronize(
                ENDPOINT,
                List.of("one"),
                List.of()
        ));
        assertEquals(List.of("create:one"), client.events);
    }

    @Test
    void retainsCreatedIdAndBlocksMoreCreatesWhenPersistenceFails() throws Exception {
        FakeClient client = new FakeClient("111111111111111111", "222222222222222222");
        FakeState state = new FakeState();
        state.failPersist = true;
        DiscordMessageSynchronizer synchronizer = new DiscordMessageSynchronizer(client, state);

        assertThrows(SyncException.class, () -> synchronizer.synchronize(
                ENDPOINT, List.of("one", "two"), List.of()
        ));
        assertEquals(true, state.createBlocked);
        assertEquals(List.of(List.of(), List.of("111111111111111111")), state.blockedStates);

        assertThrows(SyncException.class, () -> synchronizer.synchronize(
                ENDPOINT, List.of("one", "two"), List.of("111111111111111111")
        ));
        assertEquals(List.of("create:one", "edit:111111111111111111:one"), client.events);
    }

    @Test
    void doesNotPostWhenCreateIntentCannotBePersisted() {
        FakeClient client = new FakeClient("111111111111111111");
        FakeState state = new FakeState();
        state.failBlock = true;
        DiscordMessageSynchronizer synchronizer = new DiscordMessageSynchronizer(client, state);

        assertThrows(IOException.class, () -> synchronizer.synchronize(
                ENDPOINT, List.of("one"), List.of()
        ));
        assertEquals(List.of(), client.events);
    }

    @Test
    void clearsCreateIntentBeforeRetryingDefinitiveRejection() throws Exception {
        FakeClient client = new FakeClient("111111111111111111");
        client.rejectedCreateNumber = 1;
        FakeState state = new FakeState();
        DiscordMessageSynchronizer synchronizer = new DiscordMessageSynchronizer(client, state);

        assertThrows(RetryableSyncException.class, () -> synchronizer.synchronize(
                ENDPOINT, List.of("one"), List.of()
        ));
        assertEquals(false, state.createBlocked);
        assertEquals(List.of(List.of()), state.cancelledStates);

        assertEquals(
                List.of("111111111111111111"),
                synchronizer.synchronize(ENDPOINT, List.of("one"), List.of())
        );
        assertEquals(List.of("create:one", "create:one"), client.events);
    }

    private static final class FakeState implements DiscordMessageSynchronizer.MessageState {
        private final List<List<String>> savedStates = new ArrayList<>();
        private final List<List<String>> blockedStates = new ArrayList<>();
        private final List<List<String>> cancelledStates = new ArrayList<>();
        private boolean createBlocked;
        private boolean failPersist;
        private boolean failBlock;

        @Override
        public boolean isCreateBlocked() {
            return createBlocked;
        }

        @Override
        public void persist(List<String> messageIds) throws IOException {
            if (failPersist) {
                throw new IOException("simulated state storage failure");
            }
            savedStates.add(messageIds);
        }

        @Override
        public void blockCreate(List<String> knownMessageIds) throws IOException {
            if (failBlock) {
                throw new IOException("simulated write-ahead failure");
            }
            createBlocked = true;
            blockedStates.add(knownMessageIds);
        }

        @Override
        public void completeCreate(List<String> messageIds) throws IOException {
            if (failPersist) {
                blockedStates.add(messageIds);
                throw new IOException("simulated state storage failure");
            }
            savedStates.add(messageIds);
            createBlocked = false;
        }

        @Override
        public void cancelCreate(List<String> knownMessageIds) {
            cancelledStates.add(knownMessageIds);
            createBlocked = false;
        }
    }

    private static final class FakeClient implements DiscordMessageSynchronizer.MessageClient {
        private final Deque<String> createdIds = new ArrayDeque<>();
        private final List<String> missingIds = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private int createCount;
        private int failCreateNumber = -1;
        private int ambiguousCreateNumber = -1;
        private int rejectedCreateNumber = -1;

        private FakeClient(String... ids) {
            createdIds.addAll(List.of(ids));
        }

        @Override
        public String create(WebhookEndpoint endpoint, String content) throws IOException {
            createCount++;
            events.add("create:" + content);
            if (createCount == failCreateNumber) {
                throw new IOException("simulated interruption");
            }
            if (createCount == ambiguousCreateNumber) {
                throw new AmbiguousCreateException(new IOException("simulated lost response"));
            }
            if (createCount == rejectedCreateNumber) {
                throw new RetryableSyncException("simulated rate limit", 1_000L);
            }
            return createdIds.removeFirst();
        }

        @Override
        public DiscordMessageSynchronizer.EditResult edit(WebhookEndpoint endpoint, String messageId, String content) {
            events.add("edit:" + messageId + ":" + content);
            return missingIds.contains(messageId)
                    ? DiscordMessageSynchronizer.EditResult.MISSING
                    : DiscordMessageSynchronizer.EditResult.UPDATED;
        }

        @Override
        public void delete(WebhookEndpoint endpoint, String messageId) {
            events.add("delete:" + messageId);
        }
    }
}

package com.pinnaclesmp.lastseendiscord;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

final class WebhookStateManager {
    private final MessageStateStore stateStore;

    private List<String> messageIds;
    private boolean createOutcomeUnknown;
    private boolean createInProgress;
    private String webhookIdentity;
    private boolean identityBindingRequired;
    private long configurationGeneration;
    private boolean stopped;

    WebhookStateManager(MessageStateStore stateStore, MessageStateStore.State initialState) {
        this.stateStore = stateStore;
        this.messageIds = List.copyOf(initialState.messageIds());
        this.createOutcomeUnknown = initialState.createOutcomeUnknown();
        this.webhookIdentity = initialState.webhookIdentity();
        this.identityBindingRequired = initialState.identityBindingRequired();
    }

    synchronized boolean advanceConfiguration(String configuredWebhookIdentity, boolean rebindState) throws IOException {
        requireRunning();
        configurationGeneration++;

        if (configuredWebhookIdentity == null) {
            return false;
        }

        if (identityBindingRequired) {
            if (!rebindState) {
                return false;
            }
            stateStore.save(messageIds, createOutcomeUnknown, configuredWebhookIdentity);
            webhookIdentity = configuredWebhookIdentity;
            identityBindingRequired = false;
            return false;
        }

        if (!rebindState || Objects.equals(webhookIdentity, configuredWebhookIdentity)) {
            return false;
        }

        webhookIdentity = configuredWebhookIdentity;
        messageIds = List.of();
        createOutcomeUnknown = false;
        createInProgress = false;
        stateStore.save(messageIds, false, webhookIdentity);
        return true;
    }

    synchronized void shutdown() {
        if (stopped) {
            return;
        }
        stopped = true;
        configurationGeneration++;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(
                configurationGeneration,
                webhookIdentity,
                List.copyOf(messageIds),
                createOutcomeUnknown,
                createInProgress,
                stopped
        );
    }

    DiscordMessageSynchronizer.MessageState bind(long generation, String identity) {
        return new DiscordMessageSynchronizer.MessageState() {
            @Override
            public boolean isCreateBlocked() throws IOException {
                return isCreateBlockedFor(generation, identity);
            }

            @Override
            public void persist(List<String> updatedMessageIds) throws IOException {
                persistFor(generation, identity, updatedMessageIds);
            }

            @Override
            public void blockCreate(List<String> knownMessageIds) throws IOException {
                blockCreateFor(generation, identity, knownMessageIds, false);
            }

            @Override
            public void beginCreate(List<String> knownMessageIds) throws IOException {
                blockCreateFor(generation, identity, knownMessageIds, true);
            }

            @Override
            public void completeCreate(List<String> updatedMessageIds) throws IOException {
                completeCreateFor(generation, identity, updatedMessageIds);
            }

            @Override
            public void cancelCreate(List<String> knownMessageIds) throws IOException {
                cancelCreateFor(generation, identity, knownMessageIds);
            }

            @Override
            public void finishCreateAttempt() {
                finishCreateAttemptFor(identity);
            }
        };
    }

    synchronized void commitSyncResult(long generation, String identity, List<String> finalMessageIds)
            throws StaleConfigurationException {
        requireCurrent(generation, identity);
        messageIds = List.copyOf(finalMessageIds);
    }

    synchronized boolean recoverAmbiguousCreate() throws IOException {
        requireRunning();
        if (createInProgress) {
            throw new SyncException("A Discord message create request is still in progress. Wait for it to "
                    + "finish before confirming recovery.");
        }
        if (!createOutcomeUnknown) {
            return false;
        }
        stateStore.save(messageIds, false, webhookIdentity);
        createOutcomeUnknown = false;
        return true;
    }

    private synchronized boolean isCreateBlockedFor(long generation, String identity)
            throws StaleConfigurationException {
        requireCurrent(generation, identity);
        return createOutcomeUnknown;
    }

    private synchronized void persistFor(long generation, String identity, List<String> updatedMessageIds)
            throws IOException {
        requireCurrent(generation, identity);
        List<String> safeIds = List.copyOf(updatedMessageIds);
        stateStore.save(safeIds, createOutcomeUnknown, webhookIdentity);
        messageIds = safeIds;
    }

    private synchronized void blockCreateFor(
            long generation,
            String identity,
            List<String> knownMessageIds,
            boolean inProgress
    ) throws IOException {
        requireCurrent(generation, identity);
        List<String> safeIds = List.copyOf(knownMessageIds);
        stateStore.save(safeIds, true, webhookIdentity);
        messageIds = safeIds;
        createOutcomeUnknown = true;
        createInProgress = inProgress;
    }

    private synchronized void completeCreateFor(long generation, String identity, List<String> updatedMessageIds)
            throws IOException {
        if (!isCurrent(generation, identity)) {
            if (!stopped && Objects.equals(identity, webhookIdentity)) {
                createInProgress = false;
            }
            throw new StaleConfigurationException();
        }
        try {
            List<String> safeIds = List.copyOf(updatedMessageIds);
            stateStore.save(safeIds, false, webhookIdentity);
            messageIds = safeIds;
            createOutcomeUnknown = false;
        } finally {
            createInProgress = false;
        }
    }

    private synchronized void cancelCreateFor(long generation, String identity, List<String> knownMessageIds)
            throws IOException {
        if (!isCurrent(generation, identity)) {
            if (!stopped && Objects.equals(identity, webhookIdentity)) {
                createInProgress = false;
            }
            throw new StaleConfigurationException();
        }
        try {
            List<String> safeIds = List.copyOf(knownMessageIds);
            stateStore.save(safeIds, false, webhookIdentity);
            messageIds = safeIds;
            createOutcomeUnknown = false;
        } finally {
            createInProgress = false;
        }
    }

    private synchronized void finishCreateAttemptFor(String identity) {
        if (!stopped && Objects.equals(identity, webhookIdentity)) {
            createInProgress = false;
        }
    }

    private void requireRunning() throws StaleConfigurationException {
        if (stopped) {
            throw new StaleConfigurationException();
        }
    }

    private void requireCurrent(long generation, String identity) throws StaleConfigurationException {
        if (!isCurrent(generation, identity)) {
            throw new StaleConfigurationException();
        }
    }

    private boolean isCurrent(long generation, String identity) {
        return !stopped
                && generation == configurationGeneration
                && Objects.equals(identity, webhookIdentity);
    }

    record Snapshot(
            long generation,
            String webhookIdentity,
            List<String> messageIds,
            boolean createOutcomeUnknown,
            boolean createInProgress,
            boolean stopped
    ) {
    }
}

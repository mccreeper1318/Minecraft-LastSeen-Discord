package com.pinnaclesmp.lastseendiscord;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class WebhookEndpoint {
    private static final String STATE_IDENTITY_PREFIX = "discord-webhook:";
    private static final Set<String> DISCORD_HOSTS = Set.of(
            "discord.com",
            "www.discord.com",
            "canary.discord.com",
            "ptb.discord.com",
            "discordapp.com",
            "www.discordapp.com"
    );

    private final URI baseUri;
    private final String webhookId;

    private WebhookEndpoint(URI baseUri, String webhookId) {
        this.baseUri = baseUri;
        this.webhookId = webhookId;
    }

    static WebhookEndpoint parse(String configuredUrl) throws SyncException {
        try {
            URI uri = new URI(configuredUrl);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || host == null
                    || !DISCORD_HOSTS.contains(host.toLowerCase(Locale.ROOT))
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                throw invalidWebhook();
            }

            String path = trimTrailingSlash(uri.getPath());
            List<String> segments = Arrays.stream(path.split("/"))
                    .filter(segment -> !segment.isBlank())
                    .toList();
            int webhooksIndex = segments.indexOf("webhooks");
            if (webhooksIndex < 1 || segments.size() != webhooksIndex + 3) {
                throw invalidWebhook();
            }

            String webhookId = segments.get(webhooksIndex + 1);
            String webhookToken = segments.get(webhooksIndex + 2);
            if (!webhookId.matches("[0-9]{1,20}") || !webhookToken.matches("[A-Za-z0-9._-]+")) {
                throw invalidWebhook();
            }

            return new WebhookEndpoint(new URI(
                    "https",
                    uri.getAuthority(),
                    path,
                    uri.getQuery(),
                    null
            ), webhookId);
        } catch (URISyntaxException | IllegalArgumentException ex) {
            throw invalidWebhook();
        }
    }

    URI executeUri() {
        List<String> queryParts = new ArrayList<>();
        String query = baseUri.getQuery();
        if (query != null && !query.isBlank()) {
            for (String part : query.split("&")) {
                if (!part.isBlank() && !part.regionMatches(true, 0, "wait=", 0, 5)) {
                    queryParts.add(part);
                }
            }
        }
        queryParts.add("wait=true");
        return rebuild(baseUri.getPath(), String.join("&", queryParts));
    }

    URI messageUri(String messageId) throws SyncException {
        if (!isValidMessageId(messageId)) {
            throw new SyncException("A stored Discord message ID is invalid.");
        }
        return rebuild(baseUri.getPath() + "/messages/" + messageId, baseUri.getQuery());
    }

    String stateIdentity() {
        return STATE_IDENTITY_PREFIX + webhookId;
    }

    static boolean isValidStateIdentity(String identity) {
        return identity != null && identity.matches(STATE_IDENTITY_PREFIX + "[0-9]{1,20}");
    }

    static boolean isValidMessageId(String messageId) {
        return messageId != null && messageId.matches("[0-9]{1,20}");
    }

    private URI rebuild(String path, String query) {
        try {
            return new URI(baseUri.getScheme(), baseUri.getAuthority(), path, query, null);
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("Could not construct a Discord request endpoint.");
        }
    }

    private static String trimTrailingSlash(String value) {
        String result = value == null ? "" : value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static SyncException invalidWebhook() {
        return new SyncException("discord.webhook-url must be a valid HTTPS Discord webhook URL.");
    }
}

package com.pinnaclesmp.lastseendiscord;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

final class DiscordWebhookClient implements DiscordMessageSynchronizer.MessageClient {
    static final long MAX_SERVER_RETRY_DELAY_MILLIS = 60_000L;
    private static final long FALLBACK_RATE_LIMIT_DELAY_MILLIS = 2_000L;
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient httpClient;
    private final String userAgent;

    DiscordWebhookClient(String userAgent) {
        this(HttpClient.newBuilder().connectTimeout(CONNECTION_TIMEOUT).build(), userAgent);
    }

    DiscordWebhookClient(HttpClient httpClient, String userAgent) {
        this.httpClient = httpClient;
        this.userAgent = userAgent;
    }

    @Override
    public String create(WebhookEndpoint endpoint, String content) throws IOException, InterruptedException {
        HttpRequest request = baseRequest(endpoint.executeUri())
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody(content), StandardCharsets.UTF_8))
                .build();
        final HttpResponse<String> response;
        try {
            response = send(request, "create a Discord webhook message", true);
        } catch (RetryableSyncException ex) {
            if (ex.deliveryMayBeAmbiguous()) {
                throw new AmbiguousCreateException(ex);
            }
            throw ex;
        }
        ensureSuccess(response, "create a Discord webhook message");

        String messageId = JsonUtil.extractTopLevelString(response.body(), "id");
        if (!WebhookEndpoint.isValidMessageId(messageId)) {
            throw new AmbiguousCreateException(new SyncException(
                    "Discord accepted the create request but returned no valid top-level message ID."
            ));
        }
        return messageId;
    }

    @Override
    public DiscordMessageSynchronizer.EditResult edit(WebhookEndpoint endpoint, String messageId, String content)
            throws IOException, InterruptedException {
        HttpRequest request = baseRequest(endpoint.messageUri(messageId))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody(content), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request, "edit a Discord webhook message", false);
        if (isUnknownMessageResponse(response.statusCode(), response.body())) {
            return DiscordMessageSynchronizer.EditResult.MISSING;
        }
        ensureSuccess(response, "edit a Discord webhook message");
        return DiscordMessageSynchronizer.EditResult.UPDATED;
    }

    @Override
    public void delete(WebhookEndpoint endpoint, String messageId) throws IOException, InterruptedException {
        HttpRequest request = baseRequest(endpoint.messageUri(messageId)).DELETE().build();
        HttpResponse<String> response = send(request, "delete a Discord webhook message", false);
        if (!isSuccessfulDeleteResponse(response.statusCode(), response.body())) {
            ensureSuccess(response, "delete a Discord webhook message");
        }
    }

    private HttpResponse<String> send(HttpRequest request, String action, boolean serverFailureMayBeAmbiguous)
            throws IOException, InterruptedException {
        final HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException ex) {
            throw new RetryableSyncException("Discord timed out while trying to " + action + ".", ex);
        } catch (IOException ex) {
            throw new RetryableSyncException("A temporary network error occurred while trying to " + action + ".", ex);
        }

        if (response.statusCode() == 429) {
            throw new RetryableSyncException(
                    "Discord rate limited the webhook request.",
                    extractRetryAfterMillis(response)
            );
        }
        if (response.statusCode() >= 500) {
            throw new RetryableSyncException("Discord temporarily failed the webhook request (HTTP "
                    + response.statusCode() + ").", RetryableSyncException.NO_SERVER_DELAY,
                    serverFailureMayBeAmbiguous);
        }
        return response;
    }

    private long extractRetryAfterMillis(HttpResponse<String> response) {
        return boundedRetryAfterMillis(
                response.headers().firstValue("Retry-After").orElse(null),
                response.headers().firstValue("X-RateLimit-Reset-After").orElse(null),
                response.body()
        );
    }

    static long boundedRetryAfterMillis(String retryAfterHeader, String resetAfterHeader, String responseBody) {
        Double headerSeconds = parsePositiveNumber(retryAfterHeader);
        if (headerSeconds == null) {
            headerSeconds = parsePositiveNumber(resetAfterHeader);
        }
        if (headerSeconds == null) {
            headerSeconds = JsonUtil.extractTopLevelNumber(responseBody, "retry_after");
        }

        if (headerSeconds == null || !Double.isFinite(headerSeconds) || headerSeconds <= 0.0D) {
            return FALLBACK_RATE_LIMIT_DELAY_MILLIS;
        }
        double millis = headerSeconds * 1_000.0D;
        return Math.max(1L, Math.min(MAX_SERVER_RETRY_DELAY_MILLIS, Math.round(millis)));
    }

    static boolean isUnknownMessageResponse(int statusCode, String responseBody) {
        if (statusCode != 404) {
            return false;
        }
        Double discordCode = JsonUtil.extractTopLevelNumber(responseBody, "code");
        return discordCode != null && discordCode.longValue() == 10_008L;
    }

    static boolean isSuccessfulDeleteResponse(int statusCode, String responseBody) {
        return (statusCode >= 200 && statusCode < 300)
                || isUnknownMessageResponse(statusCode, responseBody);
    }

    private static Double parsePositiveNumber(String value) {
        if (value == null) {
            return null;
        }
        try {
            double number = Double.parseDouble(value.trim());
            return Double.isFinite(number) && number > 0.0D ? number : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private HttpRequest.Builder baseRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("User-Agent", userAgent);
    }

    private void ensureSuccess(HttpResponse<String> response, String action) throws SyncException {
        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            throw new SyncException("Discord could not " + action + " (HTTP " + code + ").");
        }
    }

    private String jsonBody(String content) {
        return "{\"content\":\"" + escapeJson(content) + "\"}";
    }

    private String escapeJson(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}

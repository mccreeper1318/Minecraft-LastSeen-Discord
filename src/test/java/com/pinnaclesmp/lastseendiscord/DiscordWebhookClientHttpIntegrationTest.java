package com.pinnaclesmp.lastseendiscord;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordWebhookClientHttpIntegrationTest {
    private static final String WEBHOOK_ID = "123456789012345678";
    private static final String MESSAGE_ID = "222222222222222222";

    private final Deque<ScriptedResponse> scriptedResponses = new ArrayDeque<>();
    private final List<CapturedRequest> capturedRequests = new ArrayList<>();

    private HttpServer server;
    private ExecutorService serverExecutor;
    private DiscordWebhookClient client;
    private WebhookEndpoint endpoint;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/", this::handleRequest);
        server.start();

        URI localWebhook = URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort()
                        + "/api/webhooks/" + WEBHOOK_ID + "/test_token?thread_id=42"
        );
        Constructor<WebhookEndpoint> constructor = WebhookEndpoint.class.getDeclaredConstructor(URI.class, String.class);
        constructor.setAccessible(true);
        endpoint = constructor.newInstance(localWebhook, WEBHOOK_ID);

        client = new DiscordWebhookClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                "LastSeenDiscord-http-test"
        );
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.shutdown();
        }
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void createUsesRealHttpBoundaryAndParsesOnlyTopLevelMessageId() throws Exception {
        script(200, "{\"nested\":{\"id\":\"111111111111111111\"},\"id\":\"" + MESSAGE_ID + "\"}");

        String result = client.create(endpoint, "hello \"Discord\"\nsecond line");

        assertEquals(MESSAGE_ID, result);
        CapturedRequest request = onlyRequest();
        assertEquals("POST", request.method());
        assertEquals("/api/webhooks/" + WEBHOOK_ID + "/test_token", request.uri().getPath());
        assertEquals("thread_id=42&wait=true", request.uri().getRawQuery());
        assertTrue(request.body().contains("\\\"Discord\\\""));
        assertTrue(request.body().contains("\\nsecond line"));
    }

    @Test
    void editTreatsDiscordUnknownMessageResponseAsMissing() throws Exception {
        script(404, "{\"message\":\"Unknown Message\",\"code\":10008}");

        DiscordMessageSynchronizer.EditResult result = client.edit(endpoint, MESSAGE_ID, "updated");

        assertEquals(DiscordMessageSynchronizer.EditResult.MISSING, result);
        CapturedRequest request = onlyRequest();
        assertEquals("PATCH", request.method());
        assertEquals(
                "/api/webhooks/" + WEBHOOK_ID + "/test_token/messages/" + MESSAGE_ID,
                request.uri().getPath()
        );
        assertEquals("thread_id=42", request.uri().getRawQuery());
    }

    @Test
    void rateLimitResponseUsesServerRetryDelay() {
        script(
                429,
                "{\"retry_after\":99}",
                Map.of("Retry-After", "0.25"),
                null,
                null
        );

        RetryableSyncException exception = assertThrows(
                RetryableSyncException.class,
                () -> client.edit(endpoint, MESSAGE_ID, "updated")
        );

        assertEquals(250L, exception.suggestedDelayMillis());
        assertFalse(exception.deliveryMayBeAmbiguous());
    }

    @Test
    void delayedHttpCompletionIsControlledDeterministically() throws Exception {
        CountDownLatch requestArrived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        script(
                200,
                "{\"id\":\"" + MESSAGE_ID + "\"}",
                Map.of(),
                requestArrived,
                releaseResponse
        );

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = caller.submit(() -> client.create(endpoint, "delayed"));

            assertTrue(requestArrived.await(2, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            releaseResponse.countDown();

            assertEquals(MESSAGE_ID, result.get(2, TimeUnit.SECONDS));
        } finally {
            releaseResponse.countDown();
            caller.shutdownNow();
        }
    }

    private void script(int statusCode, String body) {
        script(statusCode, body, Map.of(), null, null);
    }

    private void script(
            int statusCode,
            String body,
            Map<String, String> headers,
            CountDownLatch requestArrived,
            CountDownLatch releaseResponse
    ) {
        synchronized (scriptedResponses) {
            scriptedResponses.addLast(new ScriptedResponse(
                    statusCode,
                    body,
                    headers,
                    requestArrived,
                    releaseResponse
            ));
        }
    }

    private CapturedRequest onlyRequest() {
        synchronized (capturedRequests) {
            assertEquals(1, capturedRequests.size());
            return capturedRequests.getFirst();
        }
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        ScriptedResponse response;
        synchronized (scriptedResponses) {
            response = scriptedResponses.removeFirst();
        }

        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        synchronized (capturedRequests) {
            capturedRequests.add(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI(),
                    new String(requestBody, StandardCharsets.UTF_8)
            ));
        }

        if (response.requestArrived() != null) {
            response.requestArrived().countDown();
        }
        if (response.releaseResponse() != null) {
            try {
                if (!response.releaseResponse().await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to release scripted HTTP response.");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting to release scripted HTTP response.", ex);
            }
        }

        for (Map.Entry<String, String> header : response.headers().entrySet()) {
            exchange.getResponseHeaders().set(header.getKey(), header.getValue());
        }
        byte[] responseBody = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.statusCode(), responseBody.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(responseBody);
        }
    }

    private record ScriptedResponse(
            int statusCode,
            String body,
            Map<String, String> headers,
            CountDownLatch requestArrived,
            CountDownLatch releaseResponse
    ) {
    }

    private record CapturedRequest(String method, URI uri, String body) {
    }
}

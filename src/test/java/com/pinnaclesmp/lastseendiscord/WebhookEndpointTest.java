package com.pinnaclesmp.lastseendiscord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookEndpointTest {
    @Test
    void buildsThreadedCreateAndMessageUrisCorrectly() throws Exception {
        WebhookEndpoint endpoint = WebhookEndpoint.parse(
                "https://discord.com/api/webhooks/123456789012345678/token_value?thread_id=999&wait=false"
        );

        assertEquals(
                "https://discord.com/api/webhooks/123456789012345678/token_value?thread_id=999&wait=true",
                endpoint.executeUri().toString()
        );
        assertEquals(
                "https://discord.com/api/webhooks/123456789012345678/token_value/messages/987654321012345678?thread_id=999&wait=false",
                endpoint.messageUri("987654321012345678").toString()
        );
    }

    @Test
    void webhookStateIdentityUsesOnlyTheNonSecretWebhookId() throws Exception {
        String firstToken = "first_secret_token";
        String secondToken = "second_secret_token";
        WebhookEndpoint first = WebhookEndpoint.parse(
                "https://discord.com/api/webhooks/123456789012345678/" + firstToken
        );
        WebhookEndpoint rotatedToken = WebhookEndpoint.parse(
                "https://discord.com/api/webhooks/123456789012345678/" + secondToken
        );
        WebhookEndpoint differentWebhook = WebhookEndpoint.parse(
                "https://discord.com/api/webhooks/987654321012345678/other_token"
        );

        assertEquals("discord-webhook:123456789012345678", first.stateIdentity());
        assertEquals(first.stateIdentity(), rotatedToken.stateIdentity());
        assertNotEquals(first.stateIdentity(), differentWebhook.stateIdentity());
        assertFalse(first.stateIdentity().contains(firstToken));
        assertFalse(rotatedToken.stateIdentity().contains(secondToken));
    }

    @Test
    void malformedWebhookErrorsNeverContainTheConfiguredSecret() {
        String secret = "very_secret_webhook_token";
        SyncException exception = assertThrows(
                SyncException.class,
                () -> WebhookEndpoint.parse("http://discord.com/api/webhooks/not-an-id/" + secret)
        );

        assertFalse(exception.getMessage().contains(secret));
        assertTrue(exception.getMessage().contains("discord.webhook-url"));
    }

    @Test
    void rejectsNonDiscordHostsAndInvalidMessageIds() {
        assertThrows(SyncException.class, () -> WebhookEndpoint.parse(
                "https://example.com/api/webhooks/123456789012345678/token"
        ));
        assertFalse(WebhookEndpoint.isValidMessageId("not-a-snowflake"));
        assertTrue(WebhookEndpoint.isValidMessageId("123456789012345678"));
    }
}

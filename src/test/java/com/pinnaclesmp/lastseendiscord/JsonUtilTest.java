package com.pinnaclesmp.lastseendiscord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsonUtilTest {
    @Test
    void extractsOnlyTheTopLevelMessageId() {
        String json = "{\"author\":{\"id\":\"111111111111111111\"},\"id\":\"222222222222222222\"}";
        assertEquals("222222222222222222", JsonUtil.extractTopLevelString(json, "id"));
    }

    @Test
    void rejectsMissingNullNumericAndMalformedIds() {
        assertNull(JsonUtil.extractTopLevelString("{\"other\":\"1\"}", "id"));
        assertNull(JsonUtil.extractTopLevelString("{\"id\":null}", "id"));
        assertNull(JsonUtil.extractTopLevelString("{\"id\":123}", "id"));
        assertNull(JsonUtil.extractTopLevelString("not-json", "id"));
    }

    @Test
    void readsTopLevelRetryAfterWithoutSearchingNestedObjects() {
        String json = "{\"metadata\":{\"retry_after\":999},\"retry_after\":1.5}";
        assertEquals(1.5D, JsonUtil.extractTopLevelNumber(json, "retry_after"));
    }
}

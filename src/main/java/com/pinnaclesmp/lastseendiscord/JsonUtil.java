package com.pinnaclesmp.lastseendiscord;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

final class JsonUtil {
    private JsonUtil() {
    }

    static String extractTopLevelString(String json, String fieldName) {
        JsonElement value = topLevelValue(json, fieldName);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        return value.getAsString();
    }

    static Double extractTopLevelNumber(String json, String fieldName) {
        JsonElement value = topLevelValue(json, fieldName);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsDouble();
        } catch (NumberFormatException | UnsupportedOperationException ex) {
            return null;
        }
    }

    private static JsonElement topLevelValue(String json, String fieldName) {
        if (json == null || json.isBlank() || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject object = root.getAsJsonObject();
            return object.get(fieldName);
        } catch (JsonParseException | IllegalStateException ex) {
            return null;
        }
    }
}

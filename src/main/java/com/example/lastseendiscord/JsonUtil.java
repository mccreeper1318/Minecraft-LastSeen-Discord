package com.example.lastseendiscord;

public final class JsonUtil {
    private JsonUtil() {
    }

    public static String extractTopLevelString(String json, String fieldName) {
        if (json == null || json.isBlank()) {
            return null;
        }

        String needle = "\"" + fieldName + "\"";
        int keyIndex = json.indexOf(needle);
        if (keyIndex < 0) {
            return null;
        }

        int colonIndex = json.indexOf(':', keyIndex + needle.length());
        if (colonIndex < 0) {
            return null;
        }

        int startQuote = json.indexOf('"', colonIndex + 1);
        if (startQuote < 0) {
            return null;
        }

        StringBuilder out = new StringBuilder();
        boolean escaping = false;
        for (int i = startQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaping) {
                switch (c) {
                    case '"', '\\', '/' -> out.append(c);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (i + 4 >= json.length()) {
                            return null;
                        }
                        String hex = json.substring(i + 1, i + 5);
                        try {
                            out.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException ex) {
                            return null;
                        }
                        i += 4;
                    }
                    default -> out.append(c);
                }
                escaping = false;
                continue;
            }

            if (c == '\\') {
                escaping = true;
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
            }
        }

        return null;
    }
}

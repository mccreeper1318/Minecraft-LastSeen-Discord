diff --git a/src/main/java/com/example/lastseendiscord/DiscordSyncService.java b/src/main/java/com/example/lastseendiscord/DiscordSyncService.java
index 39b003c59a7a0fbcf81069b7ad7ab646209c75d2..016afd7ead3da00cc980d6198619b9c4f541dc53 100644
--- a/src/main/java/com/example/lastseendiscord/DiscordSyncService.java
+++ b/src/main/java/com/example/lastseendiscord/DiscordSyncService.java
@@ -1,44 +1,45 @@
 package com.example.lastseendiscord;
 
 import org.bukkit.Bukkit;
 import org.bukkit.OfflinePlayer;
 import org.bukkit.configuration.file.FileConfiguration;
 
 import java.io.IOException;
 import java.net.URI;
 import java.net.http.HttpClient;
 import java.net.http.HttpRequest;
 import java.net.http.HttpResponse;
 import java.nio.charset.StandardCharsets;
 import java.time.Instant;
 import java.time.ZoneId;
 import java.time.format.DateTimeFormatter;
 import java.util.ArrayList;
 import java.util.Comparator;
 import java.util.List;
 import java.util.Objects;
+import java.util.stream.Collectors;
 import java.util.concurrent.atomic.AtomicBoolean;
 
 public final class DiscordSyncService {
     private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd")
             .withZone(ZoneId.systemDefault());
 
     private final LastSeenDiscordPlugin plugin;
     private final HttpClient httpClient;
     private final AtomicBoolean running = new AtomicBoolean(false);
     private final AtomicBoolean pending = new AtomicBoolean(false);
 
     public DiscordSyncService(LastSeenDiscordPlugin plugin) {
         this.plugin = plugin;
         this.httpClient = HttpClient.newHttpClient();
     }
 
     public void requestSync(String reason) {
         if (pending.getAndSet(true)) {
             return;
         }
 
         Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> runSyncLoop(reason));
     }
 
     private void runSyncLoop(String initialReason) {
@@ -46,168 +47,236 @@ public final class DiscordSyncService {
             return;
         }
 
         String reason = initialReason;
         try {
             while (pending.getAndSet(false)) {
                 try {
                     syncOnce(reason);
                 } catch (Exception ex) {
                     plugin.getLogger().severe("Discord sync failed (" + reason + "): " + ex.getMessage());
                     ex.printStackTrace();
                 }
                 reason = "coalesced update";
             }
         } finally {
             running.set(false);
             if (pending.get()) {
                 requestSync("missed update");
             }
         }
     }
 
     private void syncOnce(String reason) throws IOException, InterruptedException {
         FileConfiguration config = plugin.config();
         String webhookUrl = config.getString("discord.webhook-url", "").trim();
-        String messageId = config.getString("discord.message-id", "").trim();
+        List<String> messageIds = new ArrayList<>(config.getStringList("discord.message-ids"));
+        messageIds = messageIds.stream().map(String::trim).filter(id -> !id.isBlank()).collect(Collectors.toCollection(ArrayList::new));
+
+        if (messageIds.isEmpty()) {
+            String legacyMessageId = config.getString("discord.message-id", "").trim();
+            if (!legacyMessageId.isEmpty()) {
+                messageIds.add(legacyMessageId);
+                plugin.getConfig().set("discord.message-ids", messageIds);
+                plugin.saveConfig();
+                plugin.getLogger().info("Migrated legacy discord.message-id to discord.message-ids");
+            }
+        }
 
         if (webhookUrl.isEmpty() || webhookUrl.equals("PUT_DISCORD_WEBHOOK_URL_HERE")) {
             plugin.getLogger().warning("Skipping Discord sync: discord.webhook-url is not configured.");
             return;
         }
 
-        String content = buildDiscordMessage(config);
+        List<String> chunks = buildDiscordMessageChunks(config);
+
+        List<String> finalMessageIds = new ArrayList<>(messageIds);
+        for (int i = 0; i < chunks.size(); i++) {
+            String content = chunks.get(i);
+            if (i < finalMessageIds.size()) {
+                editMessage(webhookUrl, finalMessageIds.get(i), content);
+                continue;
+            }
 
-        if (messageId.isEmpty()) {
             String createdMessageId = createMessage(webhookUrl, content);
             if (createdMessageId != null && !createdMessageId.isBlank()) {
-                plugin.getConfig().set("discord.message-id", createdMessageId);
-                plugin.saveConfig();
-                plugin.getLogger().info("Created Discord webhook message and saved message-id=" + createdMessageId + " (reason: " + reason + ")");
+                finalMessageIds.add(createdMessageId);
             }
-            return;
         }
 
-        editMessage(webhookUrl, messageId, content);
-        plugin.getLogger().info("Updated Discord webhook message (reason: " + reason + ")");
+        if (finalMessageIds.size() > chunks.size()) {
+            List<String> staleMessageIds = new ArrayList<>(finalMessageIds.subList(chunks.size(), finalMessageIds.size()));
+            for (String staleMessageId : staleMessageIds) {
+                deleteMessage(webhookUrl, staleMessageId);
+            }
+            finalMessageIds = new ArrayList<>(finalMessageIds.subList(0, chunks.size()));
+        }
+
+        plugin.getConfig().set("discord.message-ids", finalMessageIds);
+        plugin.saveConfig();
+        plugin.getLogger().info("Updated Discord webhook messages (" + chunks.size() + " chunk(s), reason: " + reason + ")");
     }
 
-    private String buildDiscordMessage(FileConfiguration config) {
+    private List<String> buildDiscordMessageChunks(FileConfiguration config) {
         int inactiveAfterDays = Math.max(1, config.getInt("activity.inactive-after-days", 30));
         boolean includeLastLoginDate = config.getBoolean("discord.include-last-login-date", false);
         String header = Objects.requireNonNullElse(config.getString("discord.header"), "").trim();
 
         long now = System.currentTimeMillis();
         long activeThreshold = now - (inactiveAfterDays * 24L * 60L * 60L * 1000L);
 
         List<PlayerStatus> statuses = new ArrayList<>();
         for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
             if (!offlinePlayer.hasPlayedBefore()) {
                 continue;
             }
 
             String name = offlinePlayer.getName();
             if (name == null || name.isBlank()) {
                 continue;
             }
 
             long lastLogin = offlinePlayer.getLastLogin();
             boolean active = lastLogin >= activeThreshold;
             statuses.add(new PlayerStatus(name, lastLogin, active));
         }
 
         statuses.sort(Comparator.comparing(PlayerStatus::name, String.CASE_INSENSITIVE_ORDER));
 
         StringBuilder sb = new StringBuilder();
         if (!header.isEmpty()) {
             sb.append(header).append("\n\n");
         }
 
         sb.append("Active threshold: past ")
                 .append(inactiveAfterDays)
                 .append(inactiveAfterDays == 1 ? " day" : " days")
                 .append("\n\n");
 
         if (statuses.isEmpty()) {
             sb.append("No players with recorded login history were found.");
-            return truncateDiscordContent(sb.toString());
+            return splitIntoChunks(sb.toString());
         }
 
         for (PlayerStatus status : statuses) {
             sb.append("- ")
                     .append(escapeDiscord(status.name()))
                     .append(" (")
                     .append(status.active() ? "active" : "inactive")
                     .append(")");
 
             if (includeLastLoginDate && status.lastLogin() > 0L) {
                 sb.append(" — ").append(DATE_FORMATTER.format(Instant.ofEpochMilli(status.lastLogin())));
             }
             sb.append("\n");
         }
 
         sb.append("\nUpdated: <t:")
                 .append(Instant.now().getEpochSecond())
                 .append(":R>");
 
-        return truncateDiscordContent(sb.toString());
+        return splitIntoChunks(sb.toString());
     }
 
-    private String truncateDiscordContent(String content) {
+    private List<String> splitIntoChunks(String content) {
         int max = 1900;
-        if (content.length() <= max) {
-            return content;
+        List<String> chunks = new ArrayList<>();
+
+        String[] lines = content.split("\\n", -1);
+        StringBuilder current = new StringBuilder();
+        for (String line : lines) {
+            String candidate = current.isEmpty() ? line : current + "\n" + line;
+            if (candidate.length() <= max) {
+                current.setLength(0);
+                current.append(candidate);
+                continue;
+            }
+
+            if (!current.isEmpty()) {
+                chunks.add(current.toString());
+                current.setLength(0);
+            }
+
+            if (line.length() <= max) {
+                current.append(line);
+                continue;
+            }
+
+            for (int start = 0; start < line.length(); start += max) {
+                int end = Math.min(start + max, line.length());
+                chunks.add(line.substring(start, end));
+            }
+        }
+
+        if (!current.isEmpty()) {
+            chunks.add(current.toString());
+        }
+
+        if (chunks.isEmpty()) {
+            chunks.add("");
         }
-        return content.substring(0, max - 24) + "\n... list truncated due to length";
+
+        return chunks;
     }
 
     private String createMessage(String webhookUrl, String content) throws IOException, InterruptedException {
         String executeUrl = webhookUrl.contains("?")
                 ? webhookUrl + "&wait=true"
                 : webhookUrl + "?wait=true";
 
         HttpRequest request = baseRequest(executeUrl)
                 .POST(HttpRequest.BodyPublishers.ofString(jsonBody(content), StandardCharsets.UTF_8))
                 .build();
 
         HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
         ensureSuccess(response, "create Discord webhook message");
 
         return JsonUtil.extractTopLevelString(response.body(), "id");
     }
 
     private void editMessage(String webhookUrl, String messageId, String content) throws IOException, InterruptedException {
         String editUrl = webhookUrl + "/messages/" + messageId;
 
         HttpRequest request = baseRequest(editUrl)
                 .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody(content), StandardCharsets.UTF_8))
                 .build();
 
         HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
         ensureSuccess(response, "edit Discord webhook message");
     }
 
+    private void deleteMessage(String webhookUrl, String messageId) throws IOException, InterruptedException {
+        String deleteUrl = webhookUrl + "/messages/" + messageId;
+
+        HttpRequest request = baseRequest(deleteUrl)
+                .DELETE()
+                .build();
+
+        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
+        ensureSuccess(response, "delete Discord webhook message");
+    }
+
     private HttpRequest.Builder baseRequest(String url) {
         return HttpRequest.newBuilder(URI.create(url))
                 .header("Content-Type", "application/json")
                 .header("User-Agent", "LastSeenDiscord/1.1.0");
     }
 
     private void ensureSuccess(HttpResponse<String> response, String action) throws IOException {
         int code = response.statusCode();
         if (code < 200 || code >= 300) {
             throw new IOException("Failed to " + action + ". HTTP " + code + " response: " + response.body());
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

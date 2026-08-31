# LastSeenDiscord

LastSeenDiscord is a Paper plugin that maintains a Discord webhook message showing every known player's activity status. Players are listed alphabetically as active or inactive using a configurable threshold, and large lists are automatically divided across multiple Discord messages.

## Requirements

- Paper 26.2
- Java 25
- A Discord channel in which you can create a webhook

## Installation

1. Download the release JAR from the repository's Releases page.
2. Stop the Minecraft server.
3. Place the JAR in the server's `plugins` directory.
4. Start the server once to create `plugins/LastSeenDiscord/config.yml`.
5. Create a webhook in the Discord channel that should contain the activity list.
6. Copy the webhook URL into `discord.webhook-url` in `config.yml`.
7. Run `/lsd reload` in game or restart the server.

Treat the Discord webhook URL like a password. Anyone who has it can post through that webhook, so do not publish `config.yml` or paste an unredacted URL into logs or support messages.

## Commands and permission

| Command | Purpose |
|---|---|
| `/lsd sync` | Queue an immediate Discord update. |
| `/lsd reload` | Reload `config.yml`, restart the automatic schedule, and queue an update. |

Both commands require `lastseendiscord.admin`, which defaults to server operators. `/lastseendiscord` is the full command name.

## Configuration

| Setting | Default | Purpose |
|---|---:|---|
| `discord.webhook-url` | placeholder | Discord webhook used for the activity messages. Official HTTPS Discord webhook URLs are required. |
| `discord.header` | `**Server Activity Status**` | Optional text placed above the list. |
| `discord.include-last-seen-date` | `false` | Adds a `YYYY-MM-DD` activity date to each player. |
| `activity.inactive-after-days` | `30` | Number of days without activity before a player is marked inactive. Minimum effective value is one day. |
| `activity.timestamp-source` | `LAST_SEEN` | Uses `LAST_SEEN` or `LAST_LOGIN`, with the other timestamp as a compatibility fallback. |
| `updates.interval-minutes` | `1440` | Automatic synchronization interval in minutes. Minimum effective value is one minute. |
| `updates.update-on-join` | `true` | Queues an update when a player joins. |
| `updates.update-on-quit` | `true` | Queues an update when a player leaves. |
| `updates.update-on-enable` | `true` | Queues an update when the plugin starts. |

The deprecated `discord.message-ids` and `discord.message-id` values are retained only to migrate installations upgrading from version 1.1.0 or earlier. Version 1.1.1 stores generated message IDs in `message-state.json`; do not edit that file while the server is running.

## How synchronization behaves

- Bukkit player data is collected on the Minecraft server thread.
- Discord requests run asynchronously and have finite connection and request timeouts.
- Requests made during an active synchronization are coalesced into a guaranteed follow-up pass.
- Discord rate limits, temporary network errors, and server errors retry with bounded exponential backoff.
- If a tracked Discord message is deleted, only that page is recreated.
- Each created or replacement message ID is saved atomically before the next page is processed.

## Updating from an older version

1. Stop the server.
2. Back up `plugins/LastSeenDiscord`.
3. Replace the old JAR with the new release JAR.
4. Start the server and confirm that `message-state.json` was created if the plugin already managed Discord messages.
5. Run `/lsd sync` and verify that the existing Discord messages update instead of being duplicated.

Existing configuration keys remain compatible with version 1.1.1.

## Troubleshooting

- **The plugin says the webhook is not configured:** Replace the placeholder with the complete webhook URL, save the file, and run `/lsd reload`.
- **The plugin rejects the webhook URL:** Create or copy a standard HTTPS webhook URL from Discord. Proxy URLs and non-Discord hosts are not accepted.
- **The list is temporarily stale:** Check the server log for a sanitized HTTP status or retry notice. Temporary failures retry automatically, and `/lsd sync` can queue another update.
- **A Discord page was deleted:** Run `/lsd sync`; the missing page is recreated and its new ID is saved automatically.
- **The build fails locally:** Use JDK 25 and run `./gradlew clean test build`.

## Building from source

```bash
./gradlew clean test build
```

The plugin JAR is written to `build/libs/lastseen-discord-<version>.jar`. Pull requests and development branches are verified by GitHub Actions. Publishing a matching `v<version>` GitHub release builds the plugin and attaches both the JAR and its SHA-256 checksum.

This project is licensed under the MIT License. See [LICENSE](LICENSE).

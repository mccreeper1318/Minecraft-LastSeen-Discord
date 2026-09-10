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
| `/lsd sync` | Queue an immediate Discord update, bypassing the join/quit debounce window. |
| `/lsd reload` | Reload and validate `config.yml`, restart the automatic schedule, and queue an update. |
| `/lsd recover-create confirm` | Resume message creation after an ambiguous Discord response. Check the channel and remove any untracked duplicate page before confirming. |

Both commands require `lastseendiscord.admin`, which defaults to server operators. `/lastseendiscord` is the full command name.

## Configuration

| Setting | Default | Purpose |
|---|---:|---|
| `discord.webhook-url` | placeholder | Discord webhook used for the activity messages. Official HTTPS Discord webhook URLs are required. Invalid values disable synchronization until corrected. |
| `discord.header` | `**Server Activity Status**` | Optional text placed above the list. |
| `discord.include-last-seen-date` | `false` | Adds a `YYYY-MM-DD` activity date to each player. |
| `activity.inactive-after-days` | `30` | Number of days without activity before a player is marked inactive. Values below `1` are normalized to `1`. |
| `activity.timestamp-source` | `LAST_SEEN` | Uses `LAST_SEEN` or `LAST_LOGIN`, with the other timestamp as a compatibility fallback. Invalid values use `LAST_SEEN`. |
| `updates.interval-minutes` | `1440` | Automatic synchronization interval. Valid range is `1`-`525600` minutes; out-of-range values are clamped. |
| `updates.update-on-join` | `true` | Requests an update when a player joins. Join/quit requests are debounced together. |
| `updates.update-on-quit` | `true` | Requests an update when a player leaves. Join/quit requests are debounced together. |
| `updates.update-on-enable` | `true` | Queues an update when the plugin starts. |
| `updates.event-debounce-seconds` | `5` | Trailing-edge debounce window for join/quit updates. Valid range is `0`-`60` seconds; values are clamped to that range and `0` disables debouncing. |

Every supported setting is validated at startup and again by `/lsd reload`. Invalid scalar values produce field-specific warnings and use a safe default or boundary value where possible. Webhook validation messages never include the configured webhook URL or token.

The deprecated `discord.message-ids` and `discord.message-id` values are retained only to migrate installations upgrading from version 1.1.0 or earlier. Malformed or duplicate legacy IDs are ignored and reported during validation. Version 1.1.1 and later store generated message IDs in `message-state.json`; malformed or duplicate IDs in that runtime-state file cause synchronization to fail closed instead of entering active state. Do not edit `message-state.json` while the server is running.

## How synchronization behaves

- Bukkit player data is collected on the Minecraft server thread; sorting, formatting, pagination, and Discord requests run asynchronously.
- Join and quit requests use a trailing-edge debounce. Each new player activity event resets the configured window, so a burst produces one synchronization using the newest captured player data.
- If the debounced activity request fires while a synchronization is already running, it is coalesced into the guaranteed follow-up pass rather than being lost.
- `/lsd sync` is always immediate and bypasses the event debounce. It does not cancel a still-pending event debounce, so a later activity pass can still run if player activity was already waiting to be synchronized.
- Discord requests run asynchronously and have finite connection and request timeouts.
- Requests made during an active synchronization are coalesced into a guaranteed follow-up pass.
- Discord rate limits, temporary network errors, and server errors retry with bounded exponential backoff.
- A create request whose delivery cannot be determined is never retried automatically. The durable safety block prevents later scheduled updates and restarts from creating another page until an administrator resolves it.
- If a tracked Discord message is deleted, only that page is recreated.
- Each created or replacement message ID is saved atomically before the next page is processed.
- Synchronization shutdown invalidates in-flight state updates, clears queued work, cancels delayed retries, and stops the webhook HTTP client so late completions cannot write stale runtime state.

## Updating from an older version

1. Stop the server.
2. Back up `plugins/LastSeenDiscord`.
3. Replace the old JAR with the new release JAR.
4. Start the server and confirm that `message-state.json` was created if the plugin already managed Discord messages.
5. Run `/lsd sync` and verify that the existing Discord messages update instead of being duplicated.

Existing configuration keys remain compatible with version 1.1.1. Newer options such as `updates.event-debounce-seconds` use their documented defaults when absent from an older configuration file. The legacy `discord.include-last-login-date` setting is still recognized when `discord.include-last-seen-date` is not explicitly set.

## Troubleshooting

- **The plugin says the webhook is not configured:** Replace the placeholder with the complete webhook URL, save the file, and run `/lsd reload`.
- **The plugin rejects the webhook URL:** Create or copy a standard HTTPS webhook URL from Discord. Proxy URLs and non-Discord hosts are not accepted. Validation errors intentionally do not print the supplied webhook URL or token.
- **A configuration value is being normalized:** Read the field-specific warning in the server log, correct the value in `config.yml`, and run `/lsd reload`. Scheduling and debounce values are clamped to their documented safe ranges.
- **The list is temporarily stale:** Check the server log for a sanitized HTTP status or retry notice. Temporary failures retry automatically, and `/lsd sync` can queue another update.
- **Join/quit updates feel delayed:** `updates.event-debounce-seconds` intentionally waits for player activity to settle before syncing. Lower the value or set it to `0` to disable event debouncing.
- **A Discord page was deleted:** Run `/lsd sync`; the missing page is recreated and its new ID is saved automatically.
- **Message creation is paused after an unknown outcome:** Inspect the Discord channel for the page that may have been created. Delete any untracked duplicate page, then run `/lsd recover-create confirm` to clear the durable safety block and synchronize again.
- **`message-state.json` cannot be read:** Synchronization stops to avoid creating duplicate messages. Reconcile the managed messages in Discord, then repair, restore, or remove the invalid state file while the server is stopped and restart the server.
- **Legacy message-ID migration fails:** Synchronization also stops if durable runtime state cannot be created. Fix the plugin data directory permissions or free disk space, then restart the server.
- **Atomic state replacement is unsupported:** Place the plugin data directory on a filesystem that supports atomic file moves. Synchronization fails closed rather than risking a corrupted or missing message-state record.
- **State-directory synchronization is unsupported:** Runtime state also requires the filesystem to support syncing directory metadata after an atomic move. The plugin fails closed if it cannot confirm that the rename is durable.
- **The build fails locally:** Use JDK 25 and run `./gradlew clean test build`.

## Building from source

```bash
./gradlew clean test build
```

The plugin JAR is written to `build/libs/lastseen-discord-<version>.jar`. Pull requests and development branches are verified by GitHub Actions. Publishing a matching `v<version>` GitHub release builds the plugin and attaches both the JAR and its SHA-256 checksum.

This project is licensed under the MIT License. See [LICENSE](LICENSE).

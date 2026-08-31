# Changelog

All notable changes to LastSeenDiscord are documented here. Versions are listed newest first.

## [1.1.1] - Unreleased

### Security

- Validate official HTTPS Discord webhook URLs and prevent webhook IDs, tokens, full URLs, response bodies, and unsanitized exception details from appearing in plugin error logs.

### Fixed

- Replace the pending/running flag race with a synchronized request queue so accepted synchronization requests cannot become permanently stranded.
- Add finite HTTP connection and request timeouts.
- Cap Discord rate-limit delays and schedule retries without sleeping the synchronization worker.
- Parse Discord responses with Gson and accept only a valid top-level message ID.
- Persist every newly created message ID atomically before continuing to the next page.
- Recreate a tracked Discord message when Discord confirms that it was deleted, then immediately persist the replacement ID.
- Retry HTTP 429 responses, temporary network failures, timeouts, and Discord server errors with bounded exponential backoff and jitter.
- Prevent ambiguous create requests from being retried when Discord may have accepted the message but its response was lost. A durable safety block now requires explicit administrator recovery before another create is attempted.
- Preserve a successfully returned message ID even if plugin shutdown begins while its create request is in flight.
- Treat a successful create response without a valid message ID as ambiguous and pause further creation.
- Retain known message IDs in memory and pause creation if runtime-state persistence fails.
- Disable synchronization when an existing `message-state.json` cannot be read instead of falling back to stale legacy state.
- Preserve the valid single-message legacy ID when the configured legacy ID list contains only blank or invalid entries.
- Treat HTTP 5xx responses to message creation as ambiguous outcomes so the create request is never retried automatically.
- Disable synchronization when legacy message IDs cannot be migrated into durable runtime state.
- Persist a write-ahead creation intent before every Discord POST, preventing message creation when runtime state is unwritable.
- Clear the write-ahead intent before retrying a create request that Discord definitively rejected, including HTTP 429 responses.
- Reject manual create recovery while a Discord create request is still in flight.
- Keep non-create persistence safety blocks recoverable by distinguishing them from active create intents.
- Treat only Discord's explicit unknown-message response as a successful deletion; propagate other HTTP 404 errors.
- Require atomic runtime-state replacement instead of risking a missing or truncated safety record on unsupported filesystems.

### Changed

- Move managed Discord message IDs from user configuration into atomic `message-state.json` runtime state. Existing `message-ids` and legacy `message-id` values migrate automatically.
- Remove the obsolete `com.example.lastseendiscord` implementation so the plugin ships one authoritative code path.
- Pin the Paper API dependency to `26.2.build.120-stable` and set the project version to `1.1.1`.

### Added

- Add deterministic regression tests for synchronization queuing, JSON parsing, webhook validation and URL construction, retry bounds, atomic message-state persistence, interrupted page creation, and deleted-message recovery.
- Add GitHub Actions validation for pushes and pull requests.
- Add a release workflow that verifies the release tag, runs the tests, and attaches one versioned plugin JAR plus its SHA-256 checksum.
- Add a server-owner README covering installation, configuration, commands, upgrades, behavior, troubleshooting, and source builds.
- Add `/lsd recover-create confirm` for safely resuming creation after an administrator resolves an ambiguous Discord response.

## [1.1.0]

### Fixed

- Split large activity lists across multiple Discord messages instead of truncating output.
- Collect Bukkit-owned player data on the server thread before making asynchronous Discord requests.
- Add compatibility fallback behavior between last-seen and last-login timestamps.
- Derive request metadata from the plugin's actual name and version.

## [1.0.0]

### Added

- Initial release with scheduled, join-triggered, quit-triggered, and manual synchronization.
- Alphabetical active/inactive player listing through a Discord webhook.
- `/lastseendiscord reload` and `/lastseendiscord sync` commands with the `/lsd` alias.

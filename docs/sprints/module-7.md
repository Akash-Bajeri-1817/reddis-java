# Module 7 - Persistence

## User Stories
- US-32: As an operator, I can snapshot the dataset to disk (SAVE, BGSAVE, automatic interval) and it is restored at startup. (FR-9)
- US-33: As an operator, I can enable an append-only file so every acknowledged write survives a crash. (FR-9)
- US-34: As an operator, I can choose the durability/performance trade-off with --appendfsync always|everysec|no. (FR-9)
- US-35: As an operator, a half-written final command after a crash is repaired automatically. (FR-9, NFR-7)
- US-36: As an operator, I can compact a grown AOF with REWRITEAOF. (FR-9)
- US-37: As a client, key timeouts keep their absolute deadline across restarts. (FR-9, FR-7)

## Acceptance Criteria
- AC-1: Snapshot files are written via temp file + fsync + atomic rename; a crash mid-save leaves the old snapshot intact.
- AC-2: Snapshots include a magic header, version and CRC32; corrupt, truncated or oversized files are rejected, never half-loaded silently.
- AC-3: Expired keys are neither saved nor restored.
- AC-4: Only successful write commands are logged; reads and failed commands are not.
- AC-5: Relative TTL commands are logged as absolute PEXPIREAT (EXPIRE with a non-positive TTL as DEL).
- AC-6: With --appendfsync always, replies are sent only after the AOF is on disk (fsync); everysec fsyncs in the background once per second.
- AC-7: A torn final command is cut off and the log is repaired; corruption in the middle of the log stops startup with a clear error.
- AC-8: REWRITEAOF atomically replaces the log with the minimal commands that rebuild the current dataset, and appending continues afterwards.
- AC-9: With AOF enabled and no AOF file, an existing snapshot is loaded and the AOF is bootstrapped from it; if both exist the AOF wins.
- AC-10: BGSAVE captures a consistent snapshot on the event loop and writes it to disk on a background thread.
- AC-11: End to end, a write acknowledged over a real socket survives a server restart.

## Definition of Done
- All acceptance criteria met, mvn test green, merged to main, tagged v0.7.0.

## Known Limitations
- Persistence works with the event-loop server only (not --blocking).
- BGSAVE and REWRITEAOF capture/replace on the event-loop thread (Redis forks a child instead), so they pause the loop in proportion to dataset size.
- No RDB compression, no multi-part AOF, no AOF auto-rewrite trigger, no disk-error recovery (a failed AOF write closes the client connection and logs).
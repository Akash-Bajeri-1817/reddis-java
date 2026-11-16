# Module 11B - Polish and Release 1.0

## Acceptance Criteria
- AC-1: Real client libraries can complete their connection handshake (HELLO falls back to RESP2, CLIENT SETNAME/SETINFO, SELECT 0, COMMAND, TIME).
- AC-2: The released jar runs with `java -jar`, its version equals the tag, and the end-to-end smoke test passes against that exact jar.
- AC-3: CI enforces the 70% coverage requirement (NFR-5).
- AC-4: README, test report, changelog, benchmarks and design records describe the final system accurately.
- AC-5: Tag v1.0.0 produces a GitHub Release with the jar attached.

## Known Limitations (documented in the README)
- RESP2 only; one database; no AUTH/TLS; no Lua; no Streams; no Cluster; no SCAN.
- No chained replication; transactions are not wrapped in MULTI/EXEC in the AOF; BGREWRITEAOF is synchronous.
# Module 6 - Event Loop (Java NIO)

## User Stories
- US-27: As an operator, one thread serves all clients, so there is no per-connection thread cost. (FR-6)
- US-28: As a client, I can send commands in any fragmentation (even byte by byte) and get correct replies. (FR-14)
- US-29: As a client, I can pipeline many commands in one write and get the replies in order. (FR-14)
- US-30: As a client, I can use MSET/MGET and be sure they are atomic. (FR-4, FR-6)
- US-31: As an operator, a slow or misbehaving client cannot block or crash the server. (NFR-4)

## Acceptance Criteria
- AC-1: A single thread ("reddis-event-loop") accepts connections and executes every command.
- AC-2: A command split across TCP packets, or arriving one byte at a time, is reassembled and answered.
- AC-3: The incremental decoder never consumes bytes of an incomplete command.
- AC-4: Pipelined commands are answered in order, with replies batched into few writes.
- AC-5: Replies larger than the socket buffer are delivered completely (partial writes + OP_WRITE).
- AC-6: A client that never reads its replies does not block other clients; reading from it is paused (backpressure).
- AC-7: Input and output buffers have hard limits (64 MB); exceeding one closes only that connection.
- AC-8: A protocol error gets an error reply and closes only that connection.
- AC-9: A command that throws does not stop the event loop.
- AC-10: Expired keys are purged by a periodic task inside the loop (no extra thread).
- AC-11: MSET/MGET are atomic: a reader never sees half of an MSET.
- AC-12: stop() closes every connection and ends the loop thread.
- AC-13: The blocking server stays available (--blocking) so Module 11 can benchmark both models.

## Definition of Done
- All acceptance criteria met, mvn test green, merged to main, tagged v0.6.0.

## Known Limitations
- Incomplete commands are re-parsed from their start on each read (simple, fine for typical sizes).
- Buffers grow but never shrink; no idle timeout; no maximum client count; inline (telnet) commands unsupported.
- DataStore still uses concurrent structures (needed by the legacy blocking server); simplifying it is optional later.
- One thread means one core: a slow command delays everyone (as in real Redis).
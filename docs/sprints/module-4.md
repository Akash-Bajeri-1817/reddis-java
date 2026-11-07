# Module 4 - Expiry (TTL)

## User Stories
- US-13: As a client, I can store a value that disappears automatically: SET k v EX 10 / PX 1500. (FR-7)
- US-14: As a client, I can add a timeout to an existing key with EXPIRE / PEXPIRE. (FR-7)
- US-15: As a client, I can read the remaining lifetime with TTL / PTTL. (FR-7)
- US-16: As a client, I can remove a timeout with PERSIST. (FR-7)
- US-17: As an operator, expired keys that nobody reads again are still freed from memory. (NFR)

## Acceptance Criteria
- AC-1: An expired key behaves exactly like a missing key for GET, EXISTS, DEL, EXPIRE and TTL.
- AC-2: TTL returns -2 for a missing key, -1 for a key without timeout, otherwise the seconds left (rounded to nearest, like Redis).
- AC-3: A plain SET removes any existing timeout on that key.
- AC-4: EXPIRE with a zero or negative time deletes the key immediately.
- AC-5: EXPIRE returns 1 if the key exists, else 0. PERSIST returns 1 only if a timeout was removed.
- AC-6: Invalid arguments return Redis-style errors (not an integer, invalid expire time, syntax error).
- AC-7: A background task removes expired keys that were never accessed again.
- AC-8: A stale expiry event can never delete a newer value or a key whose timeout was extended or removed.
- AC-9: Time is injected, so all expiry logic is unit tested without Thread.sleep.

## Definition of Done
- All acceptance criteria met, mvn test green, merged to main, tagged v0.4.0.

## Known Limitations
- Replaced or removed timeouts leave a small stale event in the expiry queue until its original time passes.
- Only EX/PX are supported on SET (no NX, XX, KEEPTTL, GET).
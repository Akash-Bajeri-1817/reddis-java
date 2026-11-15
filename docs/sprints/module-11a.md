# Module 11A - Command Completeness

## User Stories
- US-51: As a client, I can use atomic counters: INCR, DECR, INCRBY, DECRBY.
- US-52: As a client, I can APPEND to a string and read its STRLEN.
- US-53: As a client, I can take a lock with SET key value NX EX seconds (and use XX).
- US-54: As an operator, I can list keys with KEYS pattern, RENAME a key, and FLUSHALL/FLUSHDB.
- US-55: As a maintainer, one table describes every write command, so a new command cannot be forgotten by the AOF,
  replication, WATCH, the memory guard or the read-only guard.

## Acceptance Criteria
- AC-1: INCR family is atomic, keeps the key's timeout, rejects non-integers (strict, like Redis) and overflow, and never changes a value on error.
- AC-2: SET NX/XX report nil when the condition fails and then change nothing (not even the timeout).
- AC-3: RENAME moves value AND timeout, overwrites the target, errors on a missing source.
- AC-4: FLUSHALL aborts transactions that WATCH any key.
- AC-5: All new write commands survive an AOF replay and reach replicas; a SET that did not happen is never logged.
- AC-6: INCR family and APPEND count as memory-growing commands; RENAME and FLUSHALL do not.
- AC-7: A test proves every command in the spec table is actually registered.

## Known Limitations
- KEYS is O(n) and blocks the event loop (like real Redis); SCAN needs our own hash table (backlog).
- RENAME touches two keys one after another: atomic on the event loop, not under the legacy blocking server.
- SET supports NX, XX, EX, PX only (no KEEPTTL or GET).
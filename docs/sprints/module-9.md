# Module 9 - Pub/Sub and Transactions

## User Stories
- US-42: As a client, I can subscribe to channels and glob patterns and receive messages published by others. (FR-11)
- US-43: As a client, I can publish a message and learn how many subscribers received it. (FR-11)
- US-44: As a client, I can group commands with MULTI/EXEC so no other client's command runs in between. (FR-11)
- US-45: As a client, I can use WATCH for optimistic locking: EXEC aborts if a watched key changed. (FR-11)

## Acceptance Criteria
- AC-1: SUBSCRIBE/UNSUBSCRIBE/PSUBSCRIBE/PUNSUBSCRIBE send one confirmation per channel with the running subscription count.
- AC-2: PUBLISH returns the number of receivers; channel subscribers get ["message", channel, payload], pattern subscribers ["pmessage", pattern, channel, payload].
- AC-3: A subscribed client may only use (P)SUBSCRIBE, (P)UNSUBSCRIBE and PING.
- AC-4: Glob patterns support * ? [abc] [a-z] [^a] and \ escapes.
- AC-5: A disconnecting client's subscriptions and watches are removed.
- AC-6: A subscriber that never reads cannot block publishers or other clients.
- AC-7: MULTI queues commands (+QUEUED); EXEC runs them in order and returns an array of replies; DISCARD drops them.
- AC-8: A failing command inside EXEC does not roll back the others; an unknown command at queue time aborts the transaction (-EXECABORT).
- AC-9: If a WATCHed key is written by anyone (or evicted) before EXEC, EXEC returns the null array and runs nothing; EXEC/DISCARD/UNWATCH clear the watches.
- AC-10: Commands run by EXEC are logged to the AOF individually; a discarded transaction logs nothing.
- AC-11: The null array (*-1) can be encoded.

## Definition of Done
- All acceptance criteria met, mvn test green, merged to main, tagged v0.9.0.

## Known Limitations
- Wrong-arity errors surface at EXEC time (inside the reply array), not at queue time as in Redis.
- WATCH is conservative: any successful write command that targets the key counts, even if it changed nothing. Expiry does not invalidate a watch.
- Transactions are not wrapped in MULTI/EXEC in the AOF: a crash mid-append could persist part of one.
- No PUBSUB command, no QUIT/RESET, no RESP3 push types. Not available with --blocking.
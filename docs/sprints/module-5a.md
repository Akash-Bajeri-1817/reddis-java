# Module 5A - Lists, Hashes, Sets

## User Stories
- US-18: As a client, I can use lists: LPUSH, RPUSH, LPOP, RPOP, LLEN, LRANGE, LINDEX. (FR-8a)
- US-19: As a client, I can use hashes: HSET, HGET, HDEL, HGETALL, HLEN, HEXISTS. (FR-8a)
- US-20: As a client, I can use sets: SADD, SREM, SMEMBERS, SISMEMBER, SCARD. (FR-8a)
- US-21: As a client, I can ask what kind of value a key holds with TYPE. (FR-8a)
- US-22: As a client, using a command on a key of the wrong type gives a clear WRONGTYPE error. (FR-8a)

## Acceptance Criteria
- AC-1: Write commands create the key if it is missing; read commands treat a missing key as empty.
- AC-2: A collection that becomes empty is deleted automatically (EXISTS returns 0, TYPE returns none).
- AC-3: A key has exactly one type; any command of another type returns -WRONGTYPE and changes nothing.
- AC-4: SET overwrites a key of any type; DEL, EXISTS, EXPIRE, TTL and PERSIST work on every type.
- AC-5: Modifying a collection keeps its timeout; writing to an expired collection starts a fresh one.
- AC-6: LRANGE and LINDEX support negative indices and clamp out-of-range ones like Redis.
- AC-7: 16 threads pushing to one list at the same time lose no element.

## Definition of Done
- All acceptance criteria met, mvn test green, merged to main, tagged v0.5.0.

## Known Limitations
- Only single-key commands: no SINTER/SUNION, LMOVE etc. (multi-key atomicity arrives with Module 6).
- No blocking commands (BLPOP) and no LPOP/RPOP count argument.
- Iteration order of hashes and sets is insertion order here; real Redis makes no such promise.
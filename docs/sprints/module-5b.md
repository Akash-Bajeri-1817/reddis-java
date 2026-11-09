# Module 5B - Sorted Sets

## User Stories
- US-23: As a client, I can add members with scores and update them: ZADD, ZINCRBY. (FR-8b)
- US-24: As a client, I can read a member's score and rank: ZSCORE, ZRANK. (FR-8b)
- US-25: As a client, I can read members in score order, ascending or descending, optionally with scores:
  ZRANGE, ZREVRANGE [WITHSCORES]. (FR-8b)
- US-26: As a client, I can remove members and count them: ZREM, ZCARD. (FR-8b)

## Acceptance Criteria
- AC-1: Members are unique; ZADD on an existing member updates its score and is not counted as added.
- AC-2: Order is by score, then by member bytes (unsigned lexicographic) when scores tie.
- AC-3: ZRANK is 0-based and ZRANGE/ZREVRANGE use the same inclusive/negative/clamped indices as LRANGE.
- AC-4: Scores accept decimals, exponents and inf/-inf; nan, garbage and overflow are rejected with
  "ERR value is not a valid float". A ZADD with any bad score changes nothing.
- AC-5: ZSCORE/ZINCRBY print scores like Redis (1, 2.5, -3, inf), not 1.0.
- AC-6: ZINCRBY never stores NaN (inf + -inf is an error).
- AC-7: Rank and range operations are O(log n); the skip list passes a randomized comparison against a reference model.
- AC-8: Empty sorted sets are deleted automatically; TYPE reports zset; wrong-type access returns WRONGTYPE.

## Definition of Done
- All acceptance criteria met, mvn test green, merged to main, tagged v0.5.1.

## Known Limitations
- ZADD options (NX, XX, GT, LT, CH, INCR), ZRANGEBYSCORE, ZCOUNT, ZREMRANGE*, ZUNIONSTORE are not implemented.
- Multi-key operations wait for Module 6.
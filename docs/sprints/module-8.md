# Module 8 - Memory Management and Eviction

## User Stories
- US-38: As an operator, I can cap memory with --maxmemory / CONFIG SET maxmemory. (FR-10)
- US-39: As an operator, I can choose what happens when the cap is hit: reject writes or evict keys by policy. (FR-10)
- US-40: As an operator, I can see memory use and evictions (INFO, MEMORY USAGE, OBJECT IDLETIME/FREQ). (FR-10)
- US-41: As an operator, evicted keys stay evicted after a restart. (FR-10, FR-9)

## Acceptance Criteria
- AC-1: Used memory is tracked incrementally for every key and value type; a full recount always agrees (NFR-8).
- AC-2: With maxmemory > 0, a growing command (SET, MSET, LPUSH, RPUSH, HSET, SADD, ZADD, ZINCRBY) first evicts until used memory is within the limit.
- AC-3: noeviction rejects growing commands over the limit with -OOM, while reads, DEL and other shrinking commands still work.
- AC-4: LRU policies prefer long-idle keys, LFU policies prefer rarely used keys, volatile-* policies only consider keys with a TTL, volatile-ttl prefers the nearest deadline.
- AC-5: Victims are chosen from a small random SAMPLE of keys (maxmemory-samples), never by scanning all keys.
- AC-6: Expired keys found while evicting are reclaimed first and not counted as evictions.
- AC-7: Evictions are appended to the AOF as DEL.
- AC-8: maxmemory, maxmemory-policy and maxmemory-samples can be changed at runtime with CONFIG SET.
- AC-9: Reads update recency/frequency metadata; TTL, TYPE, MEMORY, OBJECT and internal lookups do not.

## Definition of Done
- All acceptance criteria met, mvn test green, merged to main, tagged v0.8.0.

## Known Limitations
- Memory is an ESTIMATE from a documented model, not measured heap usage.
- One command may overshoot the limit by its own size; at most 100 000 evictions run per command.
- Eviction runs in the event-loop thread only (--blocking mode ignores maxmemory).
- No eviction pool (Redis keeps a carry-over pool of good candidates between rounds).
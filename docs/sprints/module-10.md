# Module 10 - Replication

## User Stories
- US-46: As an operator, REPLICAOF host port turns a server into a replica that mirrors the master. (FR-12)
- US-47: As an operator, REPLICAOF NO ONE promotes a replica to a master. (FR-12)
- US-48: As a client, I can read from a replica; writes there are rejected. (FR-12)
- US-49: As an operator, a replica that reconnects catches up without a full copy when possible. (FR-12)
- US-50: As an operator, I can see roles, offsets and lag with INFO replication and ROLE. (FR-12)

## Acceptance Criteria
- AC-1: A new replica receives a consistent snapshot of the master and then every later write, in order.
- AC-2: The stream consists of the same records the AOF uses (relative TTLs already turned into absolute deadlines).
- AC-3: Replicas reject client writes with -READONLY but apply the master's stream.
- AC-4: Offsets count stream bytes. A replica that reconnects with a known replication ID and an offset still in the master's backlog gets +CONTINUE and only the missing commands; otherwise +FULLRESYNC.
- AC-5: Replicas send REPLCONF ACK <offset> every second; the master reports per-replica offset and lag in INFO.
- AC-6: A slow replica never blocks the master; one that exceeds its output buffer is dropped and resyncs later.
- AC-7: After a full resync the replica's own AOF is rewritten from the new dataset; applied commands are appended to it.
- AC-8: Evictions on the master reach replicas as DEL.
- AC-9: REPLICAOF NO ONE starts a new replication history (new ID, offset continues).
- AC-10: A full resync replaces everything the replica held before.

## Definition of Done
- All acceptance criteria met, mvn test green, merged to main, tagged v0.10.0.

## Known Limitations
- No chained replication (a replica cannot have replicas); PSYNC to a replica is refused.
- The snapshot is sent as one reply, so it must fit the 64 MB per-connection output limit.
- No heartbeat or timeouts on the link, no WAIT, no min-replicas.
- Replicas expire keys themselves using the absolute deadlines (real Redis lets the master send DEL); clock skew can make them differ briefly.
- A transaction's commands arrive individually (no MULTI/EXEC wrapper), so a replica read can see part of one.
- The replica role is not persisted: use --replicaof at startup. A restarted master gets a new replication ID, so replicas do a full resync.
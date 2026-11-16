# Changelog

## 1.0.0
Client-compatibility commands (HELLO, CLIENT, SELECT, COMMAND, TIME), benchmarks, documentation, runnable jar, CI/CD with
coverage gate and end-to-end smoke test.

## 0.11.0
INCR/DECR/INCRBY/DECRBY, APPEND, STRLEN, SET NX/XX, KEYS, RENAME, FLUSHALL/FLUSHDB; one command-spec table (CommandSpecs).

## 0.10.0
Master-replica replication: full and partial resync, backlog, REPLICAOF, ROLE, read-only replicas, INFO replication.

## 0.9.0
Pub/Sub with glob patterns; MULTI/EXEC/DISCARD/WATCH; session layer and dispatcher.

## 0.8.0
maxmemory with eight eviction policies (approximated LRU/LFU), memory accounting, CONFIG/INFO/MEMORY/OBJECT.

## 0.7.0
RDB-style snapshots (SAVE, BGSAVE), append-only file with fsync policies, crash recovery, AOF rewrite.

## 0.6.0
Single-threaded NIO event loop, incremental RESP decoder, pipelining, backpressure, MSET/MGET.

## 0.5.1 / 0.5.0
Sorted sets with a hand-written skip list; lists, hashes, sets, TYPE and WRONGTYPE.

## 0.4.0
Key expiry: EXPIRE, TTL, PERSIST, SET EX/PX; lazy and active expiration.

## 0.3.0
Concurrent clients on virtual threads; thread-safe store.

## 0.2.0
SET, GET, DEL, EXISTS and the storage layer.

## 0.1.0
TCP server, RESP protocol, PING and ECHO.
# Reddis - Requirements Specification

## 1. Purpose
Reddis is an in-memory key-value data store written in Java 21 from scratch,
inspired by Redis. It speaks the RESP protocol, so the official `redis-cli`
can connect to it. The goal is to demonstrate networking, concurrency,
data structures and persistence internals.

## 2. Scope
### In scope
- TCP server speaking RESP2
- String, List, Hash, Set, Sorted Set data types
- Key expiry (TTL)
- Persistence (snapshot and append-only log)
- Eviction when memory limit is reached
- Pub/Sub and basic transactions
- Concurrent clients

### Out of scope (for v1.0)
- Clustering and sharding
- Lua scripting
- Authentication / ACLs
- RESP3, modules, streams

## 3. Functional Requirements
| ID | Requirement | Module |
|----|-------------|--------|
| FR-1 | Server shall accept TCP connections on a configurable port (default 6379) | 1 |
| FR-2 | Server shall parse RESP arrays of bulk strings sent by clients | 1 |
| FR-3 | Server shall respond to PING and ECHO | 1 |
| FR-4 | Server shall support SET, GET, DEL, EXISTS | 2 |
| FR-5 | Server shall return a RESP error for unknown commands | 2 |
| FR-6 | Server shall serve multiple clients concurrently | 3 |
| FR-7 | Server shall support EXPIRE, PEXPIRE, TTL, PTTL, PERSIST and SET with EX/PX | 4 |
| FR-8a | Server shall support list, hash and set commands, the TYPE command and WRONGTYPE errors | 5A |
| FR-8b | Server shall support sorted-set commands | 5B |
| FR-9 | Server shall persist data and recover it after restart | 7 |
| FR-10 | Server shall evict keys when memory limit is reached | 8 |
| FR-11 | Server shall support PUBLISH/SUBSCRIBE and MULTI/EXEC | 9 |

## 4. Non-Functional Requirements
| ID | Requirement |
|----|-------------|
| NFR-1 | Compatibility: official redis-cli must work against Reddis |
| NFR-2 | Thread safety: no data corruption under concurrent access |
| NFR-3 | Performance: handle at least 50,000 simple ops/sec on a laptop |
| NFR-4 | Reliability: malformed input must never crash the server |
| NFR-5 | Maintainability: modular code, 70%+ unit test coverage on core logic |
| NFR-6 | Zero external runtime dependencies (JDK only; JUnit for tests) |

## 5. Assumptions and Constraints
- Java 21, Maven build
- Single-node deployment
- RESP2 protocol only

## 6. Milestones
| Version | Modules | Description |
|---------|---------|-------------|
| v0.1 | 1 | Server answers PING/ECHO |
| v0.2 | 2 | Basic key-value commands |
| v0.3 | 3 | Concurrent clients |
| v0.4 | 4 | Expiry |
| v0.5 | 5 | Rich data types |
| v0.6 | 6 | NIO event loop |
| v0.7 | 7 | Persistence |
| v0.8 | 8-9 | Eviction, Pub/Sub, transactions |
| v1.0 | 10-11 | Polish, benchmarks, docs |
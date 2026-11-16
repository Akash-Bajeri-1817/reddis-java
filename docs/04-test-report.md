# Test Report

## Summary

| Metric | Value |
|---|---|
| Tests run | |
| Failures / errors | 0 / 0 |
| Line coverage (JaCoCo) | % (gate: 70%) |
| Branch coverage | % |
| Smoke test (real redis-cli) | passed |

## Test strategy

| Level | What it covers | Examples |
|---|---|---|
| Unit | one class, no I/O | `RespDecoderTest`, `GlobMatcherTest`, `ReplicationBacklogTest` |
| Model-based randomized | a structure against a trusted reference, with invariants | `SkipListTest`, `DataStoreMemoryTest`, `RandomKeySetTest`, `ValueMemoryAccountingTest` |
| Integration (real sockets/files) | the server as a client sees it | `EventLoopServerTest`, `EventLoopPubSubTest`, `EventLoopPersistenceTest`, `ReplicationIntegrationTest` |
| Crash / recovery | torn writes, corruption, restarts | `SnapshotCodecTest`, `AofLoaderTest`, `PersistenceTest` |
| End to end | the packaged jar and the real `redis-cli` | `scripts/smoke-test.sh` |
| Demos (not tests) | measurements and illustrations | `RaceConditionDemo`, `EvictionSimulation`, `MemoryModelDemo` |

Techniques: seeded `Random` and an injectable `Clock` for deterministic tests; `validate()` invariant checkers
(`SkipList`, `DataStore`); `@TempDir` for file tests; polling with a timeout for asynchronous replication.

## Requirements traceability

| Requirement | Verified by |
|---|---|
| FR-1, FR-2, FR-3 (TCP, RESP, PING/ECHO) | `RespParserTest`, `RespDecoderTest`, `RespWriterTest`, `EventLoopServerTest` |
| FR-4, FR-5 (key-value commands, errors) | `KeyValueCommandsTest`, `DataStoreTest`, `MultiKeyCommandsTest`, `CounterCommandsTest`, `StringCommandsTest`, `SetNxXxTest`, `KeyspaceCommandsTest` |
| FR-6 (concurrent clients, atomic commands) | `EventLoopServerTest`, `ServerConcurrencyTest`, `DataStoreConcurrencyTest` |
| FR-7 (expiry) | `DataStoreExpiryTest`, `ExpiryCommandsTest`, `ExpireAtCommandTest`, `ExpiryPurgerTest` |
| FR-8a, FR-8b (collections, sorted sets) | `ListValueTest`, `CollectionCommandsTest`, `DataStoreTypedAccessTest`, `SkipListTest`, `SortedSetValueTest`, `SortedSetCommandsTest` |
| FR-9 (persistence) | `SnapshotCodecTest`, `AofLoaderTest`, `CommandPropagatorTest`, `PersistenceTest`, `EventLoopPersistenceTest`, `PersistenceNewCommandsTest` |
| FR-10 (memory limit, eviction) | `MemoryGuardTest`, `MemoryCommandsTest`, `EvictionPolicyTest`, `EntryMetadataTest`, `EventLoopMemoryTest` |
| FR-11 (pub/sub, transactions) | `DispatcherTest`, `PubSubBrokerTest`, `WatchRegistryTest`, `GlobMatcherTest`, `EventLoopPubSubTest`, `EventLoopTransactionPersistenceTest` |
| FR-12 (replication) | `ReplicationBacklogTest`, `ReplicationMasterTest`, `ReadOnlyGuardTest`, `ReplicationCommandsTest`, `ReplicationIntegrationTest`, `ReplicationNewCommandsTest` |
| FR-14 (partial reads/writes, pipelining) | `RespDecoderTest`, `EventLoopServerTest` |
| NFR-1 (redis-cli compatible) | `scripts/smoke-test.sh` (CI) |
| NFR-2 (thread safety) | `DataStoreConcurrencyTest`, `DataStoreTypedAccessTest` |
| NFR-3 (performance) | `docs/benchmarks.md` |
| NFR-4 (robustness to bad input) | parser/decoder tests, `EventLoopServerTest` (protocol error, failing command) |
| NFR-5 (coverage) | JaCoCo gate in `mvn verify` |
| NFR-6 (no runtime dependencies) | `pom.xml` (JUnit is test scope only) |
| NFR-7 (crash safety) | `SnapshotCodecTest`, `PersistenceTest` (torn tail, corruption) |
| NFR-8 (exact memory accounting) | `DataStoreMemoryTest`, `ValueMemoryAccountingTest` |

## Known gaps
- Windows/macOS are not covered by CI (add a matrix to find portability issues).
- No long-running soak or fuzz tests; no network-fault injection beyond connection drops.
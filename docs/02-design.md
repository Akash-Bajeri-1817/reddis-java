# Reddis - Design Document

## 1. Architecture
Four-layer architecture. Each layer depends only on the layer below it.

| Layer | Package | Responsibility |
|-------|---------|----------------|
| Network | com.reddis.server | Accept TCP connections, read/write raw bytes |
| Protocol | com.reddis.protocol | Parse and encode RESP |
| Command | com.reddis.command | Map command names to handlers, execute them |
| Storage | com.reddis.storage | Hold data in memory, expose operations |

Entry point: `com.reddis.Main`

## 2. Request Flow
client bytes -> ClientHandler -> RespParser -> CommandRouter -> Command.execute()
-> DataStore -> response object -> RespWriter -> client bytes

## 3. Key Design Patterns
- Command pattern: one class per Redis command behind a common interface
- Layered architecture: for isolation and testability

## 4. Architecture Decision Records

### ADR-1: Start with thread-per-client, migrate to NIO event loop in Module 6
Context: Redis uses a single-threaded event loop, but NIO is harder to learn.
Decision: Build the blocking version first (Modules 1-3), then replace the network layer.
Consequence: We can measure and explain the difference between both models.

### ADR-2: Values are stored as byte[], not String
Context: RESP bulk strings are binary-safe.
Decision: Keys and values travel as byte[] through protocol and storage layers.
Consequence: No data corruption from charset decoding; slightly more code.

### ADR-3: RESP2 only
Context: RESP3 adds many types that redis-cli does not require.
Decision: Implement RESP2 only.
Consequence: Smaller scope, full redis-cli compatibility.

### ADR-4: No external runtime dependencies
Decision: JDK only; JUnit is test-scoped.
Consequence: Everything is built from scratch, which is the point of the project.

### ADR-5: ByteArrayKey wrapper instead of String or raw byte[] as map key
Context: Keys arrive as byte[]. Arrays use identity-based equals/hashCode, so a raw byte[]
cannot be a HashMap key. Converting to String would corrupt non-UTF-8 binary keys (ADR-2).
Decision: Wrap keys in an immutable-by-convention ByteArrayKey with content-based
equals/hashCode and a cached hash.
Consequence: Binary-safe keys with O(1) lookup, at the cost of one small wrapper object per operation.

### ADR-6: Constructor injection for the DataStore
Context: Several commands must share one store, and tests need isolated stores.
Decision: Create the DataStore once in Main and pass it to each command's constructor.
Consequence: No global state or singletons; commands are easy to unit test.

### ADR-7: Virtual thread per connection for the blocking server
Context: Module 1 served one client at a time. Platform threads are costly (OS thread, ~1 MB
stack); a fixed pool with blocking I/O lets idle clients starve new ones.
Decision: Use Executors.newVirtualThreadPerTaskExecutor() (Java 21), one virtual thread per client.
The Server receives the ExecutorService by injection and owns its shutdown.
Consequence: Simple blocking code scales to thousands of connections. This model is temporary:
Module 6 replaces it with a single-threaded NIO event loop, as real Redis does.

### ADR-8: ConcurrentHashMap in DataStore; per-key atomicity only
Context: Many client threads share one store.
Decision: Back DataStore with ConcurrentHashMap. Each single-key operation is atomic.
Read-modify-write operations (future INCR etc.) must use atomic methods such as merge/compute.
Consequence: No lost writes or corruption. Multi-key commands (DEL a b c) are not atomic as a
group until the single-threaded event loop of Module 6.

### ADR-9: Injected java.time.Clock; wall-clock time for expiry
Context: Expiry needs "now". System.nanoTime() is monotonic but meaningless across restarts;
persistence (Module 7) and replication need absolute timestamps.
Decision: Store absolute expiry as epoch milliseconds from an injected Clock (Clock.systemUTC() in
production, MutableClock in tests).
Consequence: Expiry logic is tested instantly and deterministically. A wall-clock jump
(NTP correction) can expire keys early or late; real Redis accepts the same trade-off.

### ADR-10: Hybrid expiry: lazy on access plus active purge via DelayQueue
Context: Lazy-only expiry leaks memory for keys that are never read again. Redis samples random
keys for active expiry, but ConcurrentHashMap cannot sample randomly.
Decision: Lazy expiry on every access, plus a DelayQueue index of (key, due time) drained by a
background ExpiryPurger every 100 ms. Entries are immutable; removals are conditional
(remove(key, entry)) and every queue item is re-validated against the live entry.
Consequence: Expired keys are freed without being read; stale queue items can never delete newer
data. Known limitation: overwritten or persisted keys leave a small stale item in the queue until
its original time passes (a cancellable timer wheel or skip-list index could fix this later).

### ADR-11: Typed values behind DataStore.access() with per-key exclusivity
Context: Lists, hashes and sets are mutable and not thread-safe, but many client threads share the store.
Decision: Map values are RedisValue objects (string, list, hash, set). Collections are only touched inside
DataStore.access(key, type, factory, action), which runs inside ConcurrentHashMap.compute and is therefore
exclusive per key. Missing keys yield a fresh empty collection that is stored only if non-empty; collections
that become empty are deleted. Collections use LinkedHashMap/LinkedHashSet/ArrayDeque.
Consequence: Per-key atomic commands with no locks in command code, Redis semantics for empty and missing
keys, deterministic iteration order. Multi-key commands (SINTER, LMOVE) are not supported until Module 6.

### ADR-12: WRONGTYPE as a stackless exception translated by the router
Context: Every command on a collection must fail identically when the key holds another type.
Decision: The storage layer throws WrongTypeException (stack trace disabled); CommandRouter converts it
into the RESP error once.
Consequence: No repeated type checks in commands and cheap error paths.

### ADR-13: Sorted set = hash map + hand-written skip list with spans
Context: Sorted sets need O(1) score lookup plus ordered access and rank in O(log n). TreeMap offers order
but not rank (headSet().size() is O(n)); an order-statistic tree needs subtree sizes maintained through rotations.
Decision: Keep a HashMap<member, score> and a SkipList ordered by (score, member bytes) whose forward
pointers carry spans (as in Redis). Level promotion probability 1/4, max 32 levels. Both structures are only
mutated together inside DataStore.access(). SkipList.validate() checks all invariants for tests.
Consequence: ZRANK and ZRANGE by position are O(log n); no rebalancing code; about 1.33 pointers per node.
Time bounds are expected (probabilistic), not worst case.

### ADR-14: Strict score parsing and Redis-style score formatting
Context: Double.parseDouble accepts NaN, hex floats and suffixes (1.5f); NaN breaks ordering; -0.0 and 0.0 are
equal for Redis but not for Double.compare; Double.toString prints 1.0 and 1.0E10.
Decision: Validate scores with a decimal regex plus inf/-inf and reject overflow, normalize -0.0 to 0.0, and
print scores with BigDecimal.valueOf(d).stripTrailingZeros().toPlainString().
Consequence: Scores can never be NaN, ordering is total, and replies match redis-cli expectations.

### ADR-15: Single-threaded NIO event loop as the default server (supersedes ADR-7 for the default path)
Context: Thread-per-client (ADR-7) needs a thread-safe store and cannot make multi-key commands atomic.
Redis itself runs all commands on one thread driven by an I/O multiplexer.
Decision: EventLoopServer uses one Selector and non-blocking SocketChannels. One thread accepts, reads,
executes every command and writes. The thread-per-client Server remains available via --blocking for benchmarks.
Consequence: Every command (including MSET/MGET) is atomic without locks; cost per connection is a few KB of
buffers; one slow command delays all clients and only one core is used. DataStore keeps its concurrent
structures because the legacy server still needs them (simplifying it is optional later).

### ADR-16: Per-connection buffers, decode-from-command-start, hard limits and backpressure
Context: TCP delivers arbitrary fragments; a single thread must never block on, or buffer unboundedly for, one client.
Decision: RespDecoder returns a command or null (leaving the position untouched) and the loop re-decodes from the
command's start when more bytes arrive. Pending replies are kept in an output buffer flushed with OP_WRITE,
registered only while output is pending. Input and output buffers are capped at 64 MB (the connection is closed past
it) and reading is paused while more than 1 MB of replies is unsent.
Consequence: Correct for any fragmentation, pipelining is batched into few writes, slow readers cannot exhaust
memory. Re-parsing is O(n) per read for a large multi-argument command; a resumable parser could fix that.

### ADR-17: Periodic work runs inside the event loop
Context: Expiry purging used a separate thread that touched the shared store.
Decision: The loop's select timeout drives a periodic task (every 100 ms) that calls DataStore.purgeExpired().
Consequence: No extra thread or locking for expiry, the same model as Redis's serverCron. A long purge would delay I/O,
so purgeExpired must stay cheap (it only touches keys that are due).

### ADR-18: Snapshot by capture-on-the-loop, write-in-the-background (no fork)
Context: Redis snapshots with fork() and copy-on-write. The JVM cannot fork, and the store's collections may only
be read by the thread that runs commands.
Decision: The event-loop thread serializes the whole dataset to a byte[] (a consistent point-in-time copy); a
background thread writes it with temp file + fsync + atomic rename + directory fsync. SAVE does both steps on the
loop. The file has a magic header, version and CRC32 and is loaded defensively (lengths bounded by file size).
Consequence: Snapshots are consistent and a crash mid-save never damages the previous snapshot. The loop pauses for
the serialization (O(dataset)) and extra memory is needed for the byte[] (a fork-based design pays page copies instead).

### ADR-19: AOF logs successful writes, with absolute deadlines, flushed before replies
Context: Replaying a log must reproduce the data, but relative timeouts depend on when they run, and the durability
promise depends on when the data reaches the disk.
Decision: After each command, CommandPropagator turns it into zero or more log records: reads and failed commands
are skipped, SET ... EX/PX and EXPIRE are rewritten to PEXPIREAT (or DEL). Records are buffered and written once per
processed batch, BEFORE replies are sent (Persistence.beforeReplies), with fsync policy always, everysec (background
thread) or no. Recovery truncates a torn tail and refuses to start on mid-file corruption.
Consequence: With always, an acknowledged write is durable, and group commit amortizes the fsync over a pipeline.
Replay is deterministic. The log grows until REWRITEAOF, which is synchronous here (Redis does it in a child process).

### ADR-20: Observer hooks keep the server unaware of persistence
Decision: EventLoopServer exposes CommandHooks (afterCommand, beforeReplies); Persistence implements it and is also
driven by the loop's periodic task. Persistence only runs with the event-loop server.
Consequence: The network layer has no file I/O; persistence can be tested without sockets (PersistenceHarness).
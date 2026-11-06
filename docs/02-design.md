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
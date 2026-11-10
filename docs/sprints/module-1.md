# Module 1 - TCP Server and RESP Protocol

## User Stories
- US-1: As a client, I can connect to Reddis over TCP on a configurable port. (FR-1)
- US-2: As a client, I can send a RESP command and have it understood. (FR-2)
- US-3: As a client, I can send PING and ECHO and get correct replies. (FR-3)
- US-4: As an operator, bad input never crashes the server. (NFR-4)

## Acceptance Criteria
- AC-1: `PING` returns `+PONG`; `PING hi` returns bulk "hi".
- AC-2: `ECHO hello` returns bulk "hello"; wrong argument count returns an error.
- AC-3: Unknown commands return `-ERR unknown command ...` and the connection stays open.
- AC-4: Malformed input returns a protocol error and closes only that connection.
- AC-5: A bulk string containing `\r\n` is parsed correctly (binary-safe).
- AC-6: Input arriving one byte at a time is parsed correctly.
- AC-7: redis-cli can connect and run PING/ECHO.

## Definition of Done
- All acceptance criteria met, `mvn test` green, merged to main, tagged v0.1.0.

## Known Limitation (fixed in Module 3)
- The server handles one client at a time.
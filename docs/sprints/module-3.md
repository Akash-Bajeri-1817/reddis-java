# Module 3 - Concurrency

## User Stories
- US-10: As a client, I am not blocked by other connected clients. (FR-6)
- US-11: As a client, concurrent activity by others never corrupts or loses my data. (NFR-2)
- US-12: As an operator, I can stop the server cleanly. (operability)

## Acceptance Criteria
- AC-1: An idle connected client does not block another client's commands.
- AC-2: 16 clients doing SET/GET at the same time all get correct replies and no write is lost.
- AC-3: A value written on one connection is visible on another.
- AC-4: Concurrent writes to different keys are all kept; concurrent deletes of one key succeed exactly once.
- AC-5: stop() closes the listening port and every client connection and ends all worker threads.
- AC-6: The server can bind to port 0 (OS picks a free port) so tests never collide with a real server.

## Definition of Done
- All acceptance criteria met, mvn test green, merged to main, tagged v0.3.0.

## Known Limitations
- Multi-key commands (DEL a b c) are not atomic as a unit; each key is atomic individually.
- No limit on the number of connections.
- One (virtual) thread per connection; replaced by an event loop in Module 6.
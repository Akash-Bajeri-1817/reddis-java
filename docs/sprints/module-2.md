# Module 2 - Command Engine

## User Stories
- US-5: As a client, I can store a value under a key with SET. (FR-4)
- US-6: As a client, I can read a value with GET, and get a null reply if the key is absent. (FR-4)
- US-7: As a client, I can delete one or more keys with DEL and learn how many existed. (FR-4)
- US-8: As a client, I can check key existence with EXISTS. (FR-4)
- US-9: As a client, unknown commands and wrong argument counts produce errors, not crashes. (FR-5)

## Acceptance Criteria
- AC-1: `SET k v` returns +OK; a later `GET k` returns v.
- AC-2: `GET` on a missing key returns a null bulk string ($-1).
- AC-3: `SET` on an existing key overwrites it.
- AC-4: `DEL a b c` returns the number of keys that actually existed.
- AC-5: `EXISTS a b` returns how many of the given keys exist.
- AC-6: Keys are case-sensitive and binary-safe.
- AC-7: Keys with identical bytes in different arrays refer to the same entry.
- AC-8: Wrong argument counts return an error that names the command.

## Definition of Done
- All acceptance criteria met, mvn test green, merged to main, tagged v0.2.0.

## Known Limitation (fixed in Module 3)
- DataStore is not thread-safe. This is safe only while one client is served at a time.
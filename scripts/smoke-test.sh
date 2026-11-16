#!/usr/bin/env bash
# End-to-end smoke test: runs the PACKAGED jar and drives it with the real redis-cli.
# Usage: bash scripts/smoke-test.sh target/reddis-<version>.jar
set -euo pipefail

JAR="${1:?usage: smoke-test.sh path/to/reddis.jar}"
PORT="${REDDIS_PORT:-6390}"
WORK="$(mktemp -d)"
LOG="$WORK/server.log"
SERVER_PID=""

cli() { redis-cli -p "$PORT" "$@"; }

fail() {
  echo "FAIL: $*" >&2
  echo "--- server log ---" >&2
  cat "$LOG" >&2 || true
  exit 1
}

REPLICA_PID=""
cleanup() {
  if [[ -n "$SERVER_PID" ]]; then
    kill -9 "$SERVER_PID" 2>/dev/null || true
  fi
  if [[ -n "$REPLICA_PID" ]]; then
    kill -9 "$REPLICA_PID" 2>/dev/null || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT   # runs even when an assertion fails

# start_server [server options...]: starts the jar and waits (up to 10 s) until it answers PING
start_server() {
  java -jar "$JAR" "$PORT" "$@" >>"$LOG" 2>&1 &
  SERVER_PID=$!
  for _ in $(seq 1 50); do
    if [[ "$(cli PING 2>/dev/null || true)" == "PONG" ]]; then
      return 0
    fi
    sleep 0.2
  done
  fail "server did not answer PING within 10 seconds"
}

stop_server_crash() {    # simulates a crash: no shutdown hooks run
  if [[ -n "$SERVER_PID" ]]; then
    kill -9 "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
    SERVER_PID=""
  fi
}

stop_server_gracefully() {   # SIGTERM: the JVM runs the shutdown hook
  kill -TERM "$SERVER_PID"
  wait "$SERVER_PID" || true  # a JVM stopped by SIGTERM exits with 143
  SERVER_PID=""
}

# expect <expected output> <redis-cli arguments...>
expect() {
  local expected="$1"; shift
  local actual
  actual="$(cli "$@")"
  [[ "$actual" == "$expected" ]] || fail "redis-cli $* returned '$actual', expected '$expected'"
  echo "ok: $*"
}

echo "== 1. data types over a real network connection =="
start_server --dir "$WORK/basic"
expect PONG PING
expect "CI-Network-Check-Passed" ECHO "CI-Network-Check-Passed"
expect OK SET name arun
expect arun GET name
expect OK MSET a 1 b 2
expect $'1\n2' MGET a b
expect 3 RPUSH tasks write test deploy
expect $'write\ntest\ndeploy' LRANGE tasks 0 -1
expect 2 HSET user:1 name arun role dev
expect arun HGET user:1 name
expect 2 SADD tags java redis
expect 2 ZADD board 100 alice 250 bob
expect $'bob\nalice' ZREVRANGE board 0 -1
expect OK SET temp x EX 100
ttl="$(cli TTL temp)"
(( ttl > 0 && ttl <= 100 )) || fail "unexpected TTL: $ttl"

echo "== 2. crash recovery: AOF with fsync always, then kill -9 =="
stop_server_crash
start_server --dir "$WORK/aof" --appendonly yes --appendfsync always
expect OK SET durable yes
expect OK SET session abc EX 300
stop_server_crash
start_server --dir "$WORK/aof" --appendonly yes --appendfsync always
expect yes GET durable
ttl="$(cli TTL session)"
(( ttl > 0 && ttl <= 300 )) || fail "timeout was not restored as an absolute deadline: $ttl"

echo "== 3. maxmemory with allkeys-lru eviction =="
stop_server_crash
start_server --dir "$WORK/evict" --maxmemory 50kb --maxmemory-policy allkeys-lru
seq 1 3000 | sed 's/.*/SET key-& value-0123456789012345678901234567890123456789/' | cli >/dev/null
keys="$(cli DBSIZE)"
(( keys > 0 && keys < 1000 )) || fail "the dataset was not bounded: $keys keys"
evicted="$(cli INFO | tr -d '\r' | sed -n 's/^evicted_keys://p')"
(( evicted > 0 )) || fail "nothing was evicted"

echo "== 4. graceful shutdown writes a snapshot that survives a restart =="
stop_server_crash
start_server --dir "$WORK/rdb" --save-interval 3600
expect OK SET snap shot
stop_server_gracefully
[[ -s "$WORK/rdb/dump.rdb" ]] || fail "no snapshot was written on SIGTERM"
start_server --dir "$WORK/rdb"
expect shot GET snap

echo "== 5. newer commands, client handshake and replication =="
stop_server_crash
start_server --dir "$WORK/final"
expect 1 INCR visits
expect 42 INCRBY visits 41
expect OK SET lock owner-1 NX EX 10
expect owner-1 GET lock
expect OK RENAME lock mutex
expect mutex KEYS 'mut*'
expect OK SELECT 0
out="$(cli HELLO 3 || true)"
[[ "$out" == *NOPROTO* ]] || fail "HELLO 3 should be refused with NOPROTO, got: $out"

REPLICA_PORT=$((PORT + 1))
java -jar "$JAR" "$REPLICA_PORT" --dir "$WORK/replica" --replicaof 127.0.0.1 "$PORT" >>"$LOG" 2>&1 &
REPLICA_PID=$!
replica_cli() { redis-cli -p "$REPLICA_PORT" "$@"; }
wait_replica() {   # wait_replica <key> <expected value>
  for _ in $(seq 1 100); do
    if [[ "$(replica_cli GET "$1" 2>/dev/null || true)" == "$2" ]]; then
      return 0
    fi
    sleep 0.2
  done
  fail "the replica never saw $1=$2"
}
wait_replica visits 42                 # arrived with the full resync
expect 43 INCR visits
wait_replica visits 43                 # arrived through the live stream
out="$(replica_cli SET x 1 || true)"
[[ "$out" == *READONLY* ]] || fail "the replica accepted a write: $out"
kill -9 "$REPLICA_PID" 2>/dev/null || true
wait "$REPLICA_PID" 2>/dev/null || true
REPLICA_PID=""

echo "All smoke tests passed."
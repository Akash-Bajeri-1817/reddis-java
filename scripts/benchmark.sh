#!/usr/bin/env bash
# Usage: bash scripts/benchmark.sh <port> "<label>"
# Runs the same redis-benchmark workload against any Redis-compatible server (Reddis, real Redis, ...).
set -euo pipefail

PORT="${1:?usage: benchmark.sh <port> <label>}"
LABEL="${2:?usage: benchmark.sh <port> <label>}"
TESTS="ping,set,get,incr,lpush,rpush,lpop,rpop,sadd,hset,zadd,mset"

echo "### $LABEL (port $PORT)"
for pipeline in 1 16; do
  echo
  echo "-- 50 clients, 200000 requests, pipeline $pipeline --"
  redis-benchmark -p "$PORT" -t "$TESTS" -n 200000 -c 50 -P "$pipeline" -r 100000 -q
done
# Benchmarks

Machine: <CPU, cores, RAM, OS, Java version>
Command: redis-benchmark -t set,get -n 200000 -c 50 -q

| Server | SET ops/s | GET ops/s | SET -P 16 ops/s | GET -P 16 ops/s |
|--------|-----------|-----------|-----------------|-----------------|
| Reddis event loop |  |  |  |  |
| Reddis blocking (virtual threads) |  |  |  |  |
| Real Redis (reference) |  |  |  |  |

Notes: <what you observed; pipelining effect; CPU usage>
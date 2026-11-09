# Benchmarks

**Machine:** Windows 11 Home/Pro (amd64), Oracle JDK 21.0.6 LTS (HotSpot 64-Bit Server VM)  
**Command:** `redis-benchmark -p 6379 -t set,get -n 200000 -c 50 -q`

| Server | SET ops/s | GET ops/s | SET -P 16 ops/s | GET -P 16 ops/s |
| :--- | :--- | :--- | :--- | :--- |
| **Reddis event loop** | 29,585.80 | 29,511.58 | 425,531.91 | 368,324.12 |
| **Reddis blocking (virtual threads)** | *Pending measurement* | *Pending measurement* | *Pending measurement* | *Pending measurement* |
| **Real Redis (reference)** | *Pending measurement* | *Pending measurement* | *Pending measurement* | *Pending measurement* |

---

## Technical Observations

### 1. Pipelining Effect (`-P 16`)
* **Throughput Multiplier:** Enabling pipelining (`-P 16`) resulted in a **~14.4x improvement on SET** (from 29.5k to 425.5k ops/s) and a **~12.5x improvement on GET** (from 29.5k to 368.3k ops/s).
* **Root Cause:** Standard client interactions suffer from local TCP round-trip latency; every command requires an independent socket write, context switch, and read. With pipelining, 16 commands are packed into unified TCP frames. The `RespParser` continuously drains the read buffer while amortizing network packet overhead and system call costs across all 16 operations.

### 2. Concurrency Scaling (`-c 50` vs `-c 500`)
* At 50 concurrent connections (`-c 50`), the event loop achieved steady throughput across both read and write operations (~29.5k ops/s).
* At 500 concurrent connections (`-c 500`), throughput decreased to **18,397.57 SET ops/s** and **16,666.67 GET ops/s**. 
* **Analysis:** Because the event loop operates on a single selector thread, increasing connection concurrency by 10x introduces OS connection multiplexing overhead, poll latency, and channel scheduling contention without providing additional CPU execution parallelism.

### 3. Resource Utilization
* Single-threaded execution pinned a single CPU core near 100% capacity during high pipelined throughput (`-P 16`).
* Memory utilization remained consistent due to binary-safe buffer operations and reusing direct byte buffers for command execution without unnecessary Java `String` allocations.
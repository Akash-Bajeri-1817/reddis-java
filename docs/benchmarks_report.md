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
## Eviction hit rates (10 000 keys, cache of 1 000, Zipf s=1.0, 300 000 requests)

| Policy | Hit rate |
|--------|----------|
| exact LRU (LinkedHashMap) | 0.6753|
| allkeys-lru, 1 sample | 0.6311|
| allkeys-lru, 5 samples | 0.6703|
| allkeys-lru, 10 samples | 0.6735|
| allkeys-lfu, 5 samples | 0.7126|
| allkeys-lfu, 10 samples | 0.7277|
| allkeys-random | 0.6314|

### Key Conclusions

1. **Sampled LRU Closely Approximates True LRU at $O(1)$ Memory Overhead**
* Exact LRU requires maintaining two pointers per entry (a doubly-linked list) and updating node positions on every read and write, introducing noticeable RAM overhead and lock contention.
* Taking just 5 random candidates and evicting the oldest achieves a **67.03%** hit rate, within **0.50%** of theoretical exact LRU (67.53%).
* Increasing to 10 samples (67.35%) narrows the gap to **0.18%**, validating why Redis avoids linked-list pointer overhead in favor of sampled approximation.


2. **$N=1$ Sampling is Mathematically Equivalent to Random Eviction**
* Sampling a single candidate ($N=1$) yielded a **63.11%** hit rate, matching pure random eviction (**63.14%**).
* This confirms the sampling pool mechanics function as expected and establishes the lower-bound baseline efficiency under this access pattern.


3. **LFU Outperforms LRU Under Skewed Access Distributions**
* Sampled LFU with 10 samples achieved **72.77%**, outperforming exact LRU (67.53%) by more than **5.2 percentage points**.
* Under non-uniform (Zipfian/Power-Law) access distributions, LRU suffers from cache pollution when cold keys receive transient spikes. LFU protects high-frequency hot keys using frequency tracking rather than relying solely on access timestamps.


4. **Diminishing Returns Beyond 5 to 10 Samples**
* Scaling from 1 sample to 5 samples yields a **+3.92%** improvement for LRU.
* Scaling from 5 to 10 samples only yields a marginal gain (**+0.32%** for LRU, **+1.51%** for LFU), but doubles the CPU cycles spent sampling candidate keys during evictions.
* This demonstrates why Redis defaults to `maxmemory-samples 5`: it represents the optimal balance between hit rate and CPU overhead.
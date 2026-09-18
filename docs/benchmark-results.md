# Queue benchmark results

Measured on 2026-09-18T15:37:44.741842600Z by `QueueBenchmark` (`./mvnw verify -Pbenchmark`).

| Environment | |
|---|---|
| CPU | 13th Gen Intel(R) Core(TM) i5-1335U (12 logical cores) |
| Memory | 16.9 GB total, 0.9 GB free at start |
| OS | Windows 11 10.0 |
| JVM | Java HotSpot(TM) 64-Bit Server VM 21.0.2 |
| Database | PostgreSQL 16.15, local server |

After an unrecorded warm-up (2,000 submissions and a 2,000-job drain).

## Database baseline (plain single-row INSERT + COMMIT, no queue code)

Every job needs at least one durable commit (claim, then result + history row), so the
database's commit rate is the ceiling the queue numbers below should be read against.

- 1 thread(s): 2,000 commits in 1.38 s -> **1,448 commits/s** (0.7 ms per commit per thread)
- 8 thread(s): 2,000 commits in 0.24 s -> **8,319 commits/s** (1.0 ms per commit per thread)

## Enqueue (REST service layer, 8 client threads)

5,000 jobs submitted in 1.56 s -> **3,214 jobs/s**

## Drain throughput (backlog of due jobs, no-op handler)

| Scenario | Jobs | Instances x threads | Wall time | Throughput |
|---|---:|---:|---:|---:|
| no-op, 1 instance | 10,000 | 1 x 8 | 11.97 s | **835 jobs/s** |
| no-op, 4 instances | 10,000 | 4 x 8 | 4.73 s | **2,112 jobs/s** |

## Drain throughput with simulated I/O (20 ms per job)

| Scenario | Jobs | Instances x threads | Wall time | Throughput | Pool-bound ceiling |
|---|---:|---:|---:|---:|---:|
| I/O, 8 threads | 2,000 | 1 x 8 | 8.11 s | **247 jobs/s** | 400 jobs/s |
| I/O, 32 threads | 2,000 | 1 x 32 | 2.21 s | **903 jobs/s** | 1,600 jobs/s |

## Latency under steady load (200 jobs/s for 10 s, 1 instance x 16 threads, no-op handler)

| Poll interval | Jobs | Submit->start p50 | p95 | p99 | Submit->done p50 | p95 | p99 | max |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 100 ms | 2,000 | 71 ms | 117 ms | 121 ms | 71 ms | 117 ms | 121 ms | 135 ms |
| 1000 ms | 2,000 | 535 ms | 976 ms | 1012 ms | 535 ms | 976 ms | 1012 ms | 1021 ms |

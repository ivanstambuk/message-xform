# Synthetic Performance Testing

Cross-cutting performance validation for the message-xform engine.
Uses JMH (Java Microbenchmark Harness) with synthetic workloads —
no real gateway backends required.

**Target:** 10,000 req/s sustained throughput on development hardware.

## Tasks

- [ ] **PERF-01** — JMH micro-benchmark harness
  _Intent:_ Set up JMH infrastructure with a Gradle `jmh` source set.
  Provides repeatable, warm-up-aware benchmarks for core transform operations.
  _Implement:_
  - Add JMH Gradle plugin (`me.champeau.jmh`) and dependencies.
  - Create `core/src/jmh/java/` source set with a baseline benchmark:
    `TransformEngineBenchmark` — measures single-spec transform throughput
    (ops/sec) with a simple identity JSLT expression.
  - Include both request and response direction benchmarks.
  _Verify:_ `./gradlew jmh` runs and produces a benchmark report.

- [ ] **PERF-02** — Throughput benchmark suite (10K req/s target)
  _Intent:_ Measure whether the engine can sustain 10,000 req/s for
  representative transform workloads. Synthetic — no real HTTP backends.
  _Benchmarks:_
  - **Identity transform** — pass-through JSLT (`.`) → baseline ops/sec.
  - **Simple field mapping** — 5-field JSLT transform → ops/sec.
  - **Complex transform** — nested structure w/ array iteration → ops/sec.
  - **Chained pipeline** — 3-step apply pipeline → ops/sec.
  - Each benchmark reports: throughput (ops/sec), average latency (µs),
    p99 latency (µs), and allocation rate (bytes/op).
  _Implement:_ Multiple `@Benchmark` methods with `@BenchmarkMode` of
  `Throughput` and `AverageTime`, using `@State(Scope.Thread)`.
  _Verify:_ All benchmarks run. Identity transform exceeds 10K ops/sec.

- [ ] **PERF-03** — Memory and resource profiling benchmark
  _Intent:_ Measure memory consumption and GC pressure for sustained
  transform workloads. Provides data for sizing guidance.
  _Implement:_ Write `ResourceProfileBenchmark`:
  - Measure heap allocation per transform (bytes/op) using JMH's
    GC profiler (`-prof gc`).
  - Measure with varying payload sizes: 1 KB, 10 KB, 100 KB JSON.
  - Report: allocation rate, GC pause frequency, live set size after
    warmup.
  _Verify:_ Benchmark runs with GC profiler. Results published as
  JMH JSON output in `build/reports/jmh/`.

- [ ] **PERF-04** — CI performance gate (Gradle task + regression script)
  _Intent:_ Integrate performance benchmarks into CI pipeline. Not a hard
  gate initially — report results and flag regressions.
  _Implement:_
  - Create Gradle task `perfTest` that runs a subset of JMH benchmarks
    with reduced iterations (for CI speed).
  - Output JMH results as JSON to `build/reports/jmh/results.json`.
  - Add a shell script `scripts/perf-gate.sh` that parses the JSON and
    fails if throughput drops below a configurable threshold (default:
    5,000 ops/sec — conservative CI floor).
  _Verify:_ `./gradlew perfTest` runs in < 60s and produces a report.

## Metrics to Capture

| Metric | Tool | Target |
|--------|------|--------|
| Throughput (ops/sec) | JMH `Throughput` mode | ≥ 10,000 |
| Average latency (µs) | JMH `AverageTime` mode | ≤ 100 µs |
| p99 latency (µs) | JMH percentile sampling | ≤ 500 µs |
| Heap allocation (bytes/op) | JMH `-prof gc` | Minimize |
| GC pause frequency | JMH `-prof gc` | < 1% time |

## Commands

```bash
# Run full benchmark suite
./gradlew jmh

# Run with GC profiler
./gradlew jmh -Pjmh.profilers=gc

# CI-friendly quick run
./gradlew perfTest

# Check regression threshold
scripts/perf-gate.sh build/reports/jmh/results.json
```

# ADR-0028 – Performance Testing Strategy: Feature-Scoped NFRs + Shared Infrastructure

Date: 2026-02-08 | Status: Accepted

## Context

Feature 001 defines NFR-001-03: "Transformation latency MUST be < 5ms for a
typical message (<50KB body)." This performance target has not yet been verified
by automated benchmarks. The question arose: where should the performance testing
infrastructure and benchmark code live?

The project roadmap includes multiple gateway adapters (Features 002–008), each of
which will have its own adapter-level performance NFRs (wrapping overhead, end-to-end
latency). Feature 004 (Standalone HTTP Proxy) will need HTTP throughput load testing.
Feature 009 (Toolchain & Quality Platform) is a planned meta-feature for shared
build and quality infrastructure.

A standalone `docs/operations/performance-testing.md` file was created during Feature
001 development with PERF-01..04 tasks planning JMH infrastructure, throughput suites,
memory profiling, and CI gates. These tasks were never linked to a feature spec.

### Options Considered

- **Option A – All in Feature 001** (rejected)
  - Put JMH setup, CI gate, throughput suites, memory profiling, and the NFR-001-03
    benchmark all inside Feature 001.
  - Rejected because the performance infrastructure (JMH plugin, CI gate scripts,
    baseline tracking) is shared tooling needed by all future adapter features. Embedding
    it in Feature 001 creates either duplication or awkward cross-feature references.

- **Option B – Standalone cross-cutting feature** (rejected as sole approach)
  - Create a dedicated "Performance & Benchmarking" feature owning everything.
  - Rejected because it severs the traceability between a feature's NFR and its
    verification. "NFR-001-03 is verified by a task in Feature X" is harder to follow
    than "NFR-001-03 is verified by T-001-53 in Feature 001."

- **Option C – Hybrid: feature-scoped NFR verification + shared infrastructure** (chosen)
  - Each feature owns a lightweight benchmark task that verifies its own NFRs.
  - Feature 009 (Toolchain) owns the shared infrastructure: JMH plugin, CI gate,
    baseline tracking, and cross-feature load test harness.
  - This matches the pattern observed in the sibling project `openauth-sim`.

### Sibling Project Research (openauth-sim)

openauth-sim uses exactly this hybrid pattern:

1. **Feature-scoped microbenchmarks** — plain JUnit tests with `assumeTrue` opt-in:
   - `OcraReplayVerifierBenchmark.java` (Feature 003 / NFR-003-02, in `core-ocra/src/test/`)
   - `MapDbCredentialStoreBaselineBenchmark.java` (Feature 012 / NFR-012-01, in `core/src/test/`)
   - No JMH — custom `System.nanoTime()` harness with warmup + percentile calculation
   - Skipped by default; enabled via `-Dio.openauth.sim.benchmark=true`
   - Results stored in the feature's plan.md appendix

2. **Cross-cutting load test infrastructure** — shared tooling:
   - `tools/perf/rest-inline-node-load.js` — HTTP load harness (all protocols)
   - Gradle task wrappers: `restInlineLoadTest`, `restInlinePerfSuite`
   - `docs/3-reference/rest-inline-performance-baselines.md` — cross-protocol tracking

## Decision

### Layer 1: Feature-scoped NFR verification (each feature owns its benchmark)

Each feature with a performance NFR adds a **lightweight opt-in benchmark test**
in its module's `src/test/java/` directory. The benchmark:

- Uses plain JUnit 5 + `assumeTrue(isBenchmarkEnabled())` to skip by default.
- Uses `System.nanoTime()` with a warmup phase and measured iterations.
- Computes and logs p50, p90, p95, p99, max latency, and throughput.
- Asserts against the NFR target (soft assertion — logged, not hard-failed).
- Enable via `-Dio.messagexform.benchmark=true` or `IO_MESSAGEXFORM_BENCHMARK=true`.

For Feature 001, this means **T-001-53: TransformEngineBenchmark** verifies
NFR-001-03 (< 5ms for < 50KB payloads).

Results are recorded in the feature's `plan.md` appendix with environment metadata
(OS, CPU, Java version, timestamp).

### Layer 2: Shared infrastructure (Feature 009 — Toolchain & Quality Platform)

Feature 009 owns the cross-cutting performance tooling:

- **PERF-01**: JMH Gradle plugin + `jmh` source set (for heavyweight, CI-Ready benchmarks)
- **PERF-02**: Throughput benchmark suite (cross-feature, multi-workload)
- **PERF-03**: Memory/GC profiling benchmark
- **PERF-04**: CI performance gate + regression detection script
- **PERF-05**: Baseline tracking documentation pattern

As adapter features are implemented, they consume this infrastructure to run
their own NFR benchmarks at scale. The feature-scoped JUnit benchmarks (Layer 1)
remain as quick developer checks; the JMH suite (Layer 2) provides CI-grade
rigour.

### docs/operations/performance-testing.md

The existing standalone `docs/operations/performance-testing.md` is redesignated
as seed material for Feature 009's performance increment. It will be superseded
when Feature 009 creates a formal spec, plan, and tasks for the performance
infrastructure.

## Consequences

Positive:
- Clear traceability: each NFR has a benchmark task in its own feature.
- Shared infrastructure avoids duplication across adapter features.
- Developer-friendly: lightweight benchmarks run with a single flag, no JMH setup
  required.
- CI-grade benchmarks available when Feature 009 is implemented.
- Matches proven sibling project pattern (openauth-sim).

Negative / trade-offs:
- Two benchmark layers to maintain (lightweight JUnit + JMH). Mitigated: JUnit
  benchmarks are simple (~150 LOC) and rarely change.
- Feature 009 must be implemented before CI-grade performance gating is available.
  Mitigated: lightweight JUnit benchmarks provide immediate coverage.

Validating scenarios:
- S-001-79: TransformEngineBenchmark — identity JSLT transform p95 < 5ms for 1KB body
- S-001-80: TransformEngineBenchmark — 5-field mapping p95 < 5ms for 10KB body
- S-001-81: TransformEngineBenchmark — benchmark is skipped when flag is not set

References:
- Feature 001 spec: `docs/architecture/features/001/spec.md` (NFR-001-03)
- openauth-sim: `core-ocra/src/test/.../OcraReplayVerifierBenchmark.java` (pattern reference)
- openauth-sim: `core/src/test/.../MapDbCredentialStoreBaselineBenchmark.java` (pattern reference)
- openauth-sim: `docs/2-how-to/benchmark-ocra-verification.md` (runbook pattern)
- docs/operations/performance-testing.md (seed material for Feature 009)

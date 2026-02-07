# ADR-0007 – Layered Observability with Telemetry SPI

Date: 2026-02-07 | Status: Accepted

## Context

NFR-001-08 (ADR-0006) established structured JSON logging for profile matches. However,
production gateway deployments need broader observability: transform latency histograms,
error rates, and trace correlation for debugging across request chains.

The engine core (NFR-001-02) prohibits gateway-specific dependencies. Adding
Micrometer or OpenTelemetry directly to the core would violate this constraint.

JourneyForge (ADR-0025) solved this with a layered model: a small always-on core +
configurable extension packs, mediated by a generic event/SPI model. This keeps
telemetry dependencies out of the core while providing full observability.

### Options Considered

- **Option A – Minimal: structured error logging + request-id passthrough** (rejected)
  - Only structured JSON log lines for errors and matches. No metrics, no tracing.
  - Pros: lowest implementation cost, no new dependencies.
  - Cons: no latency histograms, no error rate dashboards — insufficient for production
    troubleshooting. Operators are blind to performance degradation.

- **Option B – Full: layered observability with telemetry SPI** (chosen)
  - Core engine emits semantic events via a `TelemetryListener` SPI. Adapters/plugins
    provide concrete OTel/Micrometer implementations. Core has zero telemetry deps.
  - Pros: production-grade visibility from day 1, respects NFR-001-02 (no direct deps),
    consistent with JourneyForge ADR-0025, extensible via packs.
  - Cons: requires designing a telemetry SPI, more implementation effort.

- **Option C – Defer to implementation** (rejected)
  - No observability spec. Each adapter implements its own.
  - Pros: simplest spec surface.
  - Cons: inconsistent observability across gateways, no portable dashboards,
    operators must learn each adapter's telemetry model.

Related ADRs:
- ADR-0006 – Profile Match Resolution (NFR-001-08 structured logging)
- JourneyForge ADR-0025 – Observability and Telemetry Layers

## Decision

We adopt **Option B – Layered observability with telemetry SPI**.

### 1. Telemetry SPI (core module — zero external deps)

The core engine defines a `TelemetryListener` interface. Adapters/plugins inject
concrete implementations:

```java
public interface TelemetryListener {
    void onTransformStarted(TransformEvent.Started event);
    void onTransformCompleted(TransformEvent.Completed event);
    void onTransformFailed(TransformEvent.Failed event);
    void onProfileMatched(MatchEvent event);
    void onSpecLoaded(SpecEvent.Loaded event);
    void onSpecRejected(SpecEvent.Rejected event);
}
```

Events carry only structural metadata — **never** body content, header values, or
sensitive data.

### 2. Core telemetry layer (always-on, strictly bounded)

Every deployment gets these signals via the SPI:

- **Metrics** (emitted as events, rendered by adapter):
  - `transform_evaluations_total{specId, specVersion, direction, outcome}`
  - `transform_duration_seconds{specId, specVersion, direction}` (histogram)
  - `profile_matches_total{profileId, specId}`
  - `spec_load_errors_total{specId, errorType}`

- **Structured log entries** (JSON format):
  - Profile match: profile id, spec id@version, path, specificity score (NFR-001-08)
  - Transform error: spec id, error type, truncated message (no body content)
  - Load rejection: spec id, validation error type

- **Trace correlation**:
  - Engine MUST propagate incoming `X-Request-ID` / `traceparent` headers through
    all log entries and telemetry events. No new traces are created by core — the
    engine participates in the caller's trace context.

### 3. Privacy and redaction rules

- Telemetry MUST NOT record: request/response bodies, raw header values,
  sensitive field content (per NFR-001-06).
- Only structural attributes: ids, versions, durations, sizes, counts, error codes.
- Attribute allowlist configurable at deployment level.

### 4. Configuration (not in spec YAML)

Telemetry is configured via engine/adapter configuration, not transform specs:

```yaml
# Engine telemetry configuration (application config)
observability:
  structured-logging: true           # always-on JSON log lines
  metrics:
    enabled: true
    exporter: prometheus             # or otlp, jmx, none
  tracing:
    propagation: w3c                 # or b3, jaeger
    sampling-rate: 0.1               # 10% sampling
```

## Consequences

Positive:
- Production-grade visibility: latency histograms, error rates, trace correlation.
- Core stays dependency-free: telemetry SPI is a plain Java interface in core module.
  Adapter modules provide OTel/Micrometer bindings.
- Consistent with JourneyForge ADR-0025 pattern: layered, privacy-first, config-driven.
- Portable: same metrics vocabulary across PingAccess, PingGateway, Kong, standalone.

Negative / trade-offs:
- Telemetry SPI adds a design surface to maintain. Events must be carefully scoped
  to avoid leaking sensitive data.
- Adapters must implement `TelemetryListener` — adds work per gateway integration.
- Operators must configure telemetry backend (Prometheus, OTLP) at deployment level.

Follow-ups:
- NFR-001-02 updated: `TelemetryListener` is a core interface, no external deps.
- Define the stable metric name vocabulary in an appendix or operator guide.
- Add `TelemetryListener` to the domain objects section of spec.md.

References:
- Feature 001 spec: `docs/architecture/features/001/spec.md` (NFR-001-08, NFR-001-09)
- JourneyForge ADR-0025: Observability and Telemetry Layers
- Validating scenarios: S-001-47, S-001-48

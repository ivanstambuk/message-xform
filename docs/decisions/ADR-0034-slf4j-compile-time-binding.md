# ADR-0034 – SLF4J Compile-Time Binding (No Custom Logging Port)

Date: 2026-02-11 | Status: Accepted

Related: ADR-0032 (Core Anti-Corruption Layer), ADR-0033 (Core Port Value Objects)

## Context

ADR-0032 establishes that core's public API must not expose third-party types.
This raised a natural question: should SLF4J receive the same treatment as
Jackson? Should core define its own `XformLogger` port interface, with each
adapter providing an SLF4J-backed implementation?

### The Problem SLF4J Shares with Jackson

Both Jackson and SLF4J are used internally by core and also shipped by gateway
runtimes:

| Dependency | Core uses internally? | Gateways ship? | In core's public API? |
|-----------|----------------------|----------------|----------------------|
| Jackson   | Yes (JSLT engine)    | Yes (PA 2.17.0, etc.) | **No** (hidden behind `byte[]` — ADR-0032) |
| SLF4J     | Yes (logging)        | Yes (PA 1.7.36, etc.) | **No** (internal only) |

The key difference: **Jackson types were in core's public API** (`JsonNode` in
`Message`, `TransformResult`, `TransformContext`), creating compile-time
coupling between core and every adapter. SLF4J types have **never** been in
core's public API — `Logger` is used internally within core classes, never
passed to or received from adapters.

### Options Considered

#### Option A — Compile against lowest SLF4J version (chosen)

Core declares `implementation` dependency on SLF4J 1.7.36 (the lowest version
shipped by any supported gateway). The compiler prevents use of 2.x-only APIs
(fluent logging). At runtime, each gateway provides its own SLF4J version on
the classpath — core's compiled bytecode works with all of them.

```kotlin
// core/build.gradle.kts
dependencies {
    implementation(libs.slf4j.api)  // 1.7.36
}
```

Additionally, a source-scan build guard (`checkSlf4jCompat` task) detects
SLF4J 2.x fluent API usage at build time, preventing accidental regression:

```
> Task :core:checkSlf4jCompat
✓ SLF4J compatibility check passed (43 files scanned)
```

#### Option B — Custom logging port interface (rejected)

Core defines its own `XformLogger` interface. Each adapter provides an
SLF4J-backed implementation via SPI:

```java
// Core port interface
public interface XformLogger {
    void info(String msg, Object... args);
    void debug(String msg, Object... args);
    void error(String msg, Throwable t);
    boolean isDebugEnabled();
}

// SLF4J adapter (each adapter provides this)
public class Slf4jXformLogger implements XformLogger {
    private final org.slf4j.Logger delegate;
    // ... delegates all calls
}
```

#### Option C — Relocate SLF4J inside core's shadow JAR (rejected)

Package-relocate SLF4J alongside Jackson (`org.slf4j` →
`io.messagexform.internal.slf4j`). Core gets its own isolated SLF4J.

## Decision

We adopt **Option A — compile against the lowest SLF4J version**.

### Why Option B (Custom Port) Is Over-Engineering

1. **SLF4J is already a facade.** SLF4J stands for "Simple Logging Facade for
   Java." It *is* the abstraction layer. A custom `XformLogger` wrapping SLF4J
   creates a "facade over a facade" — the exact anti-pattern SLF4J was designed
   to eliminate.

2. **SLF4J's API has been binary-stable for 20 years.** The core `Logger`
   interface (`info()`, `debug()`, `warn()`, `error()`) has not changed since
   SLF4J 1.0 (2004). Code compiled against 1.7.x runs on 2.x runtimes and
   vice versa. The only breaking change between 1.x and 2.x is the **provider
   discovery mechanism** (static binder → `ServiceLoader`), which is invisible
   to application code.

3. **SLF4J types don't leak through core's API.** Unlike Jackson (where
   `JsonNode` was in `Message`, `TransformResult`, etc.), SLF4J `Logger` is
   only used inside core class bodies. No adapter ever receives or passes a
   `Logger`. There is no version coupling at the API boundary.

4. **Maintenance burden for zero benefit.** A custom port requires:
   - An `XformLogger` interface + `XformLoggerFactory` in core
   - An `Slf4jXformLogger` + SPI registration in *every* adapter module
   - Feature parity maintenance (MDC, Markers, fluent API, `isXxxEnabled()`)
   - Extra method dispatch per log call (performance cost in hot paths)
   - SLF4J-aware APM agents and log analyzers may not recognize the custom type

5. **Industry consensus: nobody does this.** Spring, Apache projects, Micronaut,
   Quarkus — all use SLF4J directly. The only notable exception is Hibernate
   (via JBoss Logging), which wraps SLF4J for **internationalization and message
   IDs** — not for version isolation.

### Why Option C (Relocate SLF4J) Is Harmful

Relocating SLF4J breaks its fundamental design contract. SLF4J's architecture
requires **exactly one binding** on the classpath, discovered by class name.
Relocating the API (`org.slf4j` → `io.messagexform.internal.slf4j`) creates a
second, isolated SLF4J universe inside core. This means:

- Core's logs would not route to the gateway's logging backend
- Gateway operators would lose visibility into transform engine behaviour
- SLF4J bridges (`jcl-over-slf4j`, `log4j-over-slf4j`) would not capture
  core's logs
- APM/observability tools cannot correlate core logs with gateway logs

This is the opposite of what operators need. Logging is inherently
**cross-cutting** — it must participate in the host's logging infrastructure.

### The JBoss Logging Precedent

Hibernate uses JBoss Logging, which is a custom facade over SLF4J/Log4j/JUL.
Their motivations — and why they don't apply to us:

| JBoss Logging Motivation | Applies to message-xform? |
|--------------------------|---------------------------|
| **i18n message IDs** — structured, translatable log messages with unique codes | No — transform engine logs are operational, not user-facing |
| **API stability insurance** — insulate JBoss ecosystem from SLF4J changes | No — SLF4J's API has been stable for 20 years; risk is near-zero |
| **Flexible backend discovery** — auto-probe for JBoss LogManager, Log4j, SLF4J, JUL | No — we only target JVM gateways that guarantee an SLF4J binding |
| **Ecosystem scale** — JBoss has 100+ projects sharing the facade | No — message-xform has one core + a few adapters |

### How Compile-Time Validation Works

Core compiles against SLF4J 1.7.36 — the lowest version shipped by any
Gateway we support. This provides **compile-time enforcement**:

```
SLF4J 1.7 API (core compiles against)
  │
  ├── info(String, Object...)     ✅ Exists since 1.0
  ├── debug(String, Object...)    ✅ Exists since 1.0
  ├── warn(String, Object...)     ✅ Exists since 1.0
  ├── error(String, Throwable)    ✅ Exists since 1.0
  ├── isDebugEnabled()            ✅ Exists since 1.0
  │
  └── atInfo().setMessage(...)    ❌ Compile error — 2.x only
      atDebug().addKeyValue(...)  ❌ Compile error — 2.x only
```

At runtime, each adapter provides whatever SLF4J version its gateway ships:

| Adapter | Runtime SLF4J | Core's 1.7 bytecode works? |
|---------|---------------|---------------------------|
| PingAccess 9.0 | 1.7.36 | ✅ Exact match |
| Standalone (Logback) | 2.0.x | ✅ Binary compatible — 1.7 API is a subset |
| Future gateway (any) | Any ≥ 1.0 | ✅ Core Logger interface unchanged since 1.0 |

### Comparison with Jackson (ADR-0032)

| Factor | Jackson | SLF4J |
|--------|---------|-------|
| **Types in core's public API?** | Yes (was `JsonNode`) — **required isolation** | No (`Logger` is internal) |
| **API stability across versions** | Breaking changes (e.g., `fields()` → `properties()` in 2.21) | Stable since 2004 (20 years) |
| **Port type needed?** | Yes → `MessageBody`, `HttpHeaders` (ADR-0033) | No — SLF4J itself is the port |
| **Relocation needed?** | Yes — prevents classpath collision | No — must share host's binding |
| **Version matters at runtime?** | No (relocated, core has its own) | No (binary compatible across all versions) |
| **Nature of dependency** | Data model (types in API) | Cross-cutting concern (logging) |

## Consequences

Positive:
- **Zero new types** — no `XformLogger`, no SPI registration, no adapter
  boilerplate.
- **Full operator visibility** — core's logs flow through the gateway's
  configured logging backend (Logback, Log4j, etc.).
- **Compile-time safety** — building against 1.7 prevents 2.x-only usage.
  The `checkSlf4jCompat` build guard adds a second layer of defence.
- **APM/observability compatibility** — SLF4J MDC, Markers, and structured
  logging work end-to-end because core shares the host's SLF4J binding.
- **Industry-standard practice** — follows the universal Java library pattern
  (depend on `slf4j-api`, let the host provide the binding).

Negative / trade-offs:
- **No fluent API in core** — core cannot use SLF4J 2.x fluent logging
  (`logger.atInfo().setMessage(...)`) because it compiles against 1.7. This
  is a minor ergonomic limitation. The traditional `logger.info("msg {}", arg)`
  pattern is functionally equivalent.
- **Version floor dependency** — if a future gateway ships SLF4J 0.x (pre-1.0),
  core would need adjustment. In practice, no relevant gateway ships SLF4J
  older than 1.7.

References:
- ADR-0032: Core Anti-Corruption Layer (byte boundary)
- ADR-0033: Core-Owned Port Value Objects
- SLF4J Manual: https://www.slf4j.org/manual.html
- SLF4J Compatibility: https://www.slf4j.org/faq.html#compatibility
- JBoss Logging: https://github.com/jboss-logging/jboss-logging

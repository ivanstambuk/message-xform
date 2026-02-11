# ADR-0031 – PA-Provided Dependencies (No Jackson Relocation)

Date: 2026-02-11 | Status: Accepted (partially superseded by ADR-0032)

> **Note:** [ADR-0032](ADR-0032-core-anti-corruption-layer.md) supersedes the
> Jackson version coupling aspect of this ADR. Core engine no longer compiles
> against PA's Jackson — it bundles and relocates its own copy behind a
> `byte[]` boundary. This ADR remains valid for: PA adapter's `compileOnly`
> SLF4J/Jakarta/PA SDK declarations, the flat classpath model, the version-
> locked release strategy, and the runtime version guard.

## Context

Feature 002 (PingAccess Adapter) needs Jackson for JSON processing — both in
the core engine (JSLT, SpecParser, TransformEngine) and at the adapter layer
(session context building via `Identity.getAttributes()`, plugin configuration
deserialization). The PingAccess SDK API directly exposes Jackson types in its
public interfaces:

- `Identity.getAttributes()` → `JsonNode`
- `SessionStateSupport.setAttribute(String, JsonNode)` → takes `JsonNode`
- `PluginConfiguration` uses `@JsonProperty`, `@JsonUnwrapped`

The original spec (FR-002-09) mandated Jackson **relocation** via Gradle
Shadow (`com.fasterxml.jackson.*` → `io.messagexform.shaded.jackson.*`) as
a defensive measure, because PA's classloading model was undocumented and we
assumed PA might isolate plugin classloaders from its own libraries.

This assumption carried significant costs:

| Cost | Impact |
|------|--------|
| Dual `ObjectMapper` pattern | One shaded, one PA-native — confusing and error-prone |
| Boundary conversion | Every `JsonNode` crossing PA↔adapter boundary serialized to `byte[]` and back |
| Compile-time ≠ runtime names | Stack traces use shaded package names |
| Increased JAR size | +2-3 MB of bundled Jackson |
| Code complexity | 4 extra conversion paths in `buildSessionContext()` |

### Investigation (Spike A)

We reverse-engineered PingAccess 9.0.1's classloading model through static
analysis — without building a diagnostic plugin:

1. **Extracted `run.sh`** from the Docker image (`/opt/out/instance/bin/run.sh`).
   Line 59 constructs the classpath:
   ```
   CLASSPATH="${CLASSPATH}:${SERVER_ROOT_DIR}/lib/*:${SERVER_ROOT_DIR}/deploy/*"
   ```
   This is a **flat classpath** — `lib/*` (PA libraries) and `deploy/*`
   (plugin JARs) share the same JVM application classloader.

2. **Decompiled bytecode** with `javap -c -p`:
   - `Bootstrap.invokeMain()` uses `this.getClass().getClassLoader().loadClass()`
     — no custom classloader.
   - `ServiceFactory.getImplClasses()` and
     `ConfigurablePluginPostProcessor.getClasses()` both call
     `ServiceLoader.load(Class)` — single-argument form, uses the thread
     context classloader (= application classloader on a flat classpath).
   - No class in any PA JAR creates a `URLClassLoader` or custom classloader
     for plugin isolation.

3. **Confirmed Jackson versions** in `/opt/server/lib/`:
   - `jackson-databind-2.17.0.jar`
   - `jackson-core-2.17.0.jar`
   - `jackson-annotations-2.17.0.jar`
   - `jackson-datatype-jdk8-2.17.0.jar`
   - `jackson-datatype-jsr310-2.17.0.jar`

Full evidence: `docs/research/spike-pa-classloader-model.md`.

### Options Considered

- **Option A – `compileOnly` Jackson** (chosen)
  - Declare Jackson as `compileOnly` in the adapter module. Don't bundle it.
    PA provides Jackson 2.17.0 at runtime on the shared classpath.
  - Pros: no relocation, no boundary conversion, no dual ObjectMapper, smaller
    JAR, simpler code, SDK-aligned.
  - Cons: adapter is version-locked to PA's Jackson version; if PA upgrades
    Jackson with breaking changes, the adapter must be updated.

- **Option B – Jackson relocation** (rejected — original design)
  - Bundle and relocate Jackson in the shadow JAR. Use boundary conversion
    (serialize to `byte[]`, deserialize back) at every PA↔adapter `JsonNode`
    crossing.
  - Rejected: solves a problem that does not exist (classloader isolation).
    On PA's flat classpath, relocation actually *causes* `ClassCastException`
    because `io.messagexform.shaded.jackson.databind.JsonNode` and
    `com.fasterxml.jackson.databind.JsonNode` are different classes within the
    same classloader.

- **Option C – Bundle Jackson without relocating** (rejected)
  - Include Jackson JARs in the shadow JAR but don't relocate.
  - Rejected: creates two copies of `com.fasterxml.jackson.*` on the
    classpath (PA's and ours). Which one loads is JVM-implementation-specific
    and unpredictable.

Related ADRs:
- ADR-0025 – Adapter Lifecycle SPI (gateway adapter contract)
- ADR-0030 – Session Context Binding (`$session`, uses `JsonNode` from PA's SDK)

## Decision

We adopt **Option A – `compileOnly` Jackson, no relocation**.

### Concrete changes

1. **Jackson** (`jackson-databind`, `jackson-core`, `jackson-annotations`)
   declared as `compileOnly` in `adapter-pingaccess/build.gradle.kts`, pinned
   to PA 9.0.1's version (2.17.0).

2. **Shadow JAR `exclude`** strips Jackson classes — they are never bundled.

3. **Boundary conversion removed** — `Identity.getAttributes()` returns the
   same `JsonNode` class the adapter uses. Direct `session.set(key, paNode)`
   works without serialization round-trip.

4. **Dual ObjectMapper removed** — single `ObjectMapper` instance for all
   adapter JSON operations.

5. **Other PA-provided dependencies** follow the same `compileOnly` pattern:

   | Library | PA 9.0.1 Version | Scope |
   |---------|-----------------|-------|
   | `jackson-databind` | 2.17.0 | `compileOnly` |
   | `jackson-core` | 2.17.0 | `compileOnly` |
   | `jackson-annotations` | 2.17.0 | `compileOnly` |
   | `slf4j-api` | 1.7.36 | `compileOnly` |
   | `jakarta.validation-api` | 3.1.1 | `compileOnly` |
   | `jakarta.inject-api` | 2.0.1 | `compileOnly` |
   | PingAccess SDK | 9.0.1.0 | `compileOnly` |
   | **SnakeYAML** | **not shipped** | `implementation` (bundle) |

6. **Version-locked release strategy** — adapter version `9.0.x` targets
   PA `9.0.*`. When PA upgrades, run the dependency extraction script
   (`scripts/pa-extract-deps.sh`), update pinned versions, and release a
   new adapter version.

7. **Runtime version guard** in `configure()` — warn if PA's runtime Jackson
   version differs from the adapter's compiled-against version.

8. **`TransformResultSummary` remains a plain record** — it already uses only
   primitive/String fields. The original justification ("avoid Jackson
   relocation issues at the ExchangeProperty boundary") is no longer relevant,
   but the design is still correct (simple, serialization-free cross-rule state).

### Classloader model (verified)

```
┌─────────────────────────────────────────────────────────┐
│              JVM Bootstrap Classloader                   │
└───────────────────────┬─────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────┐
│     jdk.internal.loader.ClassLoaders$AppClassLoader      │
│                                                          │
│  Classpath:                                              │
│    /opt/server/lib/*                     (146 JARs)      │
│    /opt/server/deploy/*                  (plugin JARs)   │
│                                                          │
│  SAME classloader → SAME Class objects → NO isolation   │
└─────────────────────────────────────────────────────────┘
```

## Consequences

Positive:
- Eliminates all boundary conversion code — ~20 lines of complex
  serialization/deserialization logic in `buildSessionContext()`.
- Removes dual `ObjectMapper` pattern — one less instance variable, one less
  concept for contributors to understand.
- Shadow JAR size drops by ~2-3 MB (no bundled Jackson).
- Stack traces show real class names, not shaded package names.
- Follows the same pattern as PA's own SDK samples — ecosystem-aligned.
- `session.set(key, identity.getAttributes().get(key))` works directly.

Negative / trade-offs:
- Adapter is version-locked to PA's Jackson version. If PA ships a Jackson
  version with a breaking change in the APIs the adapter uses (e.g.,
  `Identity.getAttributes()`), the adapter must be updated. Mitigated by:
  Jackson's exceptional backwards compatibility track record, and the runtime
  version guard.
- Adapter versions must track PA versions (e.g., adapter `9.0.x` for PA
  `9.0.y`). This is standard practice in plugin ecosystems (Elasticsearch,
  IntelliJ, Jenkins, Gradle).

Validating evidence:
- `docs/research/spike-pa-classloader-model.md` — full reverse-engineering log.
- `docs/research/spike-pa-dependency-extraction.md` — dependency inventory.

References:
- Feature 002 spec: `docs/architecture/features/002/spec.md` (FR-002-06, FR-002-09)
- SDK guide: `docs/architecture/features/002/pingaccess-sdk-guide.md` (§6, §9)
- ADR-0025: Adapter Lifecycle SPI
- ADR-0030: Session Context Binding

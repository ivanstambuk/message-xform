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

### Investigation

We reverse-engineered PingAccess 9.0.1's classloading model through static
analysis (bytecode decompilation and startup script inspection) — without
building or deploying a diagnostic plugin.

1. **Flat classpath** — `run.sh` (line 59) constructs the classpath:
   ```
   CLASSPATH="${CLASSPATH}:${SERVER_ROOT_DIR}/lib/*:${SERVER_ROOT_DIR}/deploy/*"
   ```
   PA places **all** JARs — platform libs (`lib/*`, 146 JARs) AND plugin
   JARs (`deploy/*`) — on the same flat classpath. The JVM is launched with
   a standard `-classpath` flag (`com.pingidentity.pa.cli.Starter`). There is
   no OSGi, no module system, no custom classloader creation.

2. **Bytecode evidence** (decompiled with `javap -c -p`):
   - `Bootstrap.invokeMain()` uses `this.getClass().getClassLoader().loadClass()`
     — no child classloader is created.
   - `ServiceFactory.getImplClasses()` calls `ServiceLoader.load(Class)` —
     the single-argument form, which uses the thread context classloader
     (= the application classloader on a flat classpath).
   - `ConfigurablePluginPostProcessor.getClasses()` uses the same
     `ServiceLoader.load(Class)` pattern.
   - Scanning all PA JARs (`grep -iE "classload|URLClass"`) found only
     `LocalizationResourceClassLoaderUtils` — used solely for loading i18n
     resource bundles (`.properties`), not for plugin isolation.
   - Discovered plugin classes are registered as **Spring prototype beans**
     via `BeanDefinitionBuilder.genericBeanDefinition(implClass).setScope("prototype")`
     — no classloader boundary between PA and plugins.

3. **PA 9.0.1 library inventory** (extracted from `/opt/server/lib/`, 146 JARs):

   | Library | Version | Available to plugins? |
   |---------|---------|----------------------|
   | `jackson-databind` | 2.17.0 | ✅ Yes |
   | `jackson-core` | 2.17.0 | ✅ Yes |
   | `jackson-annotations` | 2.17.0 | ✅ Yes |
   | `jackson-datatype-jdk8` | 2.17.0 | ✅ Yes |
   | `jackson-datatype-jsr310` | 2.17.0 | ✅ Yes |
   | `slf4j-api` | 1.7.36 | ✅ Yes (SLF4J 1.x, not 2.x) |
   | `log4j-api` / `log4j-slf4j-impl` | 2.24.3 | ✅ Yes (Log4j2, **not** Logback) |
   | `jakarta.validation-api` | 3.1.1 | ✅ Yes |
   | `jakarta.inject-api` | 2.0.1 | ✅ Yes |
   | `hibernate-validator` | 7.0.5.Final | ✅ Yes |
   | `commons-lang3` | 3.14.0 | ✅ Yes |
   | `guava` | 33.1.0-jre | ✅ Yes |
   | `spring-context` / `spring-beans` | 6.2.11 | ✅ Yes |
   | `netty-*` | 4.1.127.Final | ✅ Yes (PA uses Netty, not Jetty) |
   | `third-party-jackson-core` | 2.17.131 | N/A — AWS SDK's own shaded copy (`software.amazon.awssdk.thirdparty.jackson`), irrelevant to plugins |
   | SnakeYAML | — | ❌ **Not shipped** — must bundle |
   | Logback | — | ❌ **Not shipped** — PA uses Log4j2 |

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

6. **Dual version catalog** — PA-provided dependency versions are managed in
   a separate catalog (`gradle/pa-provided.versions.toml`, generated by
   `scripts/pa-extract-deps.sh`) registered as `paProvided` in
   `settings.gradle.kts`. This keeps them isolated from core's own
   version choices in `gradle/libs.versions.toml`.

   Build integration option analysis:
   - **Option A – Version catalog overlay** (initially favoured for simplicity):
     PA versions in `libs.versions.toml` with `pa-` prefix. Simple but mixes
     PA-controlled and developer-controlled versions in one file.
   - **Option B – Separate platform module** (`pa-platform-9.0/`):
     Clean separation via Gradle `java-platform`. Overkill for a single PA
     version target.
   - **Option C – Separate generated TOML** (chosen):
     Script generates `gradle/pa-provided.versions.toml`, registered as a
     second version catalog (`paProvided`). Fully automated, separate
     namespace (`paProvided.` vs `libs.`), no mixing.

7. **Version parity release strategy** — see
   [ADR-0035](ADR-0035-adapter-version-parity.md) (supersedes original §7).
   Adapter version mirrors the gateway version exactly, with a 4th segment
   for adapter patches: `<PA_MAJOR>.<PA_MINOR>.<PA_PATCH>.<ADAPTER_PATCH>`.

8. **Runtime misdeployment guard** — see
   [ADR-0035](ADR-0035-adapter-version-parity.md) §"Runtime misdeployment
   guard" (supersedes original §8). Simplified from a severity matrix to a
   single mismatch warning.

9. **`TransformResultSummary` remains a plain record** — it already uses only
   primitive/String fields. The original justification ("avoid Jackson
   relocation issues at the ExchangeProperty boundary") is no longer relevant,
   but the design is still correct (simple, serialization-free cross-rule state).

### Classloader model (verified)

```
┌─────────────────────────────────────────────────────────┐
│              JVM Bootstrap Classloader                   │
│    (java.base, java.lang, java.util, etc.)              │
└───────────────────────┬─────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────┐
│     jdk.internal.loader.ClassLoaders$AppClassLoader      │
│                                                          │
│  Classpath:                                              │
│    /opt/server/conf/                                     │
│    /opt/server/resource/bc/non-fips/*    (BouncyCastle)  │
│    /opt/server/lib/*                     (146 JARs)      │
│    /opt/server/deploy/*                  (plugin JARs)   │
│                                                          │
│  ┌────────────────────┐  ┌──────────────────────┐       │
│  │  PA Platform JARs  │  │  Plugin JARs         │       │
│  │  (engine, admin,   │  │  (our adapter,       │       │
│  │   sdk, jackson,    │  │   deployed to         │       │
│  │   spring, netty,   │  │   /opt/server/deploy/) │       │
│  │   log4j, ...)      │  │                       │       │
│  └────────────────────┘  └──────────────────────┘       │
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
- Bytecode decompilation of `pingaccess-cli-9.0.1.0.jar` (Bootstrap, Starter),
  `pingaccess-engine-9.0.1.0.jar` (PluginRegistry, ConfigurablePluginPostProcessor),
  and `pingaccess-sdk-9.0.1.0.jar` (ServiceFactory) — all confirm flat classpath
  with standard `ServiceLoader` discovery and no custom classloader creation.

References:
- Feature 002 spec: `docs/architecture/features/002/spec.md` (FR-002-06, FR-002-09)
- SDK guide: `docs/architecture/features/002/pingaccess-sdk-guide.md` (§6, §9)
- ADR-0025: Adapter Lifecycle SPI
- ADR-0030: Session Context Binding

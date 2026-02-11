# Core Byte-Boundary Refactor — Implementation Plan

Status: **📋 Planned** | Created: 2026-02-11 | ADR: 0032

> **Goal:** Remove all third-party types (Jackson, SLF4J 2.x-only APIs) from
> core's public API. Core becomes a self-contained engine that accepts
> `byte[]` / `Map` / `String` and returns the same. Gateway adapters convert
> between their SDK-specific types and core's opaque boundary.

## Architecture Before / After

### Before (ADR-0031 — current)

```
Adapter  ──JsonNode──►  Core Engine  ──JsonNode──►  Adapter
           shared                        shared
```

Jackson `JsonNode` is a **shared boundary type**. All adapters and core
MUST use the same Jackson version at runtime.

### After (ADR-0032 — target)

```
Adapter  ──byte[]──►  Core Engine  ──byte[]──►  Adapter
         opaque       (own Jackson,    opaque
                       relocated)
```

Core hides Jackson behind `byte[]`. Each adapter uses its gateway's Jackson
(or no Jackson at all) independently.

---

## Phase 1: Core API Refactor (Feature 001)

> **Scope:** Core module only. No adapter changes yet.

### 1.1 — Change `Message.body` from `JsonNode` to `byte[]`

**File:** `core/src/main/java/io/messagexform/core/model/Message.java`

```java
// BEFORE
public record Message(JsonNode body, ...)

// AFTER
public record Message(byte[] body, ...)
```

- `body` is raw JSON bytes (UTF-8).
- Null/absent bodies represented as `null` or `new byte[0]`.
- Remove `import com.fasterxml.jackson.databind.JsonNode` from `Message.java`.

### 1.2 — Change `Message.sessionContext` from `JsonNode` to `Map<String, Object>`

```java
// BEFORE
public record Message(..., JsonNode sessionContext)

// AFTER
public record Message(..., Map<String, Object> sessionContext)
```

- Session context becomes a nested `Map<String, Object>` structure.
- Adapters convert from their gateway-specific types (PA's `JsonNode`,
  Kong's `JsonObject`, etc.) into plain Java maps.
- Core internally converts `Map<String, Object>` → `JsonNode` for JSLT
  `$session` binding.

### 1.3 — Change `TransformResult.errorResponse` from `JsonNode` to `byte[]`

**File:** `core/src/main/java/io/messagexform/core/model/TransformResult.java`

```java
// BEFORE
public JsonNode errorResponse();

// AFTER
public byte[] errorResponse();
```

### 1.4 — Change `TransformContext` helper methods

**File:** `core/src/main/java/io/messagexform/core/model/TransformContext.java`

The `headersAsJson()`, `statusAsJson()`, etc. methods become **internal** to
`TransformEngine`. They should not be in the public model class.

```java
// BEFORE (public API)
public record TransformContext(..., JsonNode sessionContext) {
    public JsonNode headersAsJson() { ... }
    public JsonNode sessionContextAsJson() { ... }
}

// AFTER (public API — clean, no Jackson)
public record TransformContext(
    Map<String, String> headers,
    Map<String, List<String>> headersAll,
    Integer status,
    Map<String, String> queryParams,
    Map<String, String> cookies,
    Map<String, Object> sessionContext  // plain Java types
)

// JsonNode conversion moves to TransformEngine (internal)
```

### 1.5 — Update `TransformEngine` internals

**File:** `core/src/main/java/io/messagexform/core/engine/TransformEngine.java`

```java
public TransformResult transform(Message message, Direction direction, TransformContext ctx) {
    // 1. Parse body bytes → internal JsonNode
    JsonNode bodyNode;
    if (message.body() == null || message.body().length == 0) {
        bodyNode = NullNode.getInstance();
    } else {
        bodyNode = internalMapper.readTree(message.body());
    }

    // 2. Build internal context bindings (headersAsJson, etc.)
    ObjectNode contextBindings = buildContextBindings(ctx);

    // 3. Run JSLT transform
    JsonNode result = jsltTransform.apply(bodyNode);

    // 4. Serialize back to byte[]
    byte[] outputBytes = internalMapper.writeValueAsBytes(result);

    return TransformResult.success(
        message.withBody(outputBytes).withHeaders(transformedHeaders)
    );
}
```

### 1.6 — Add `Message.withBody()` convenience method

```java
public Message withBody(byte[] newBody) {
    return new Message(newBody, headers, headersAll, statusCode,
        contentType, requestPath, requestMethod, queryString, sessionContext);
}
```

---

## Phase 2: Jackson Relocation in Core (Build System)

### 2.1 — Add Shadow plugin to core module

**File:** `core/build.gradle.kts`

```kotlin
plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

shadowJar {
    archiveClassifier.set("")  // replace main JAR

    // Relocate Jackson — hidden from adapters
    relocate("com.fasterxml.jackson", "io.messagexform.internal.jackson")

    // Relocate JSLT (it depends on Jackson)
    relocate("com.schibsted.spt.data.jslt", "io.messagexform.internal.jslt")

    // Relocate JSON Schema Validator
    relocate("com.networknt", "io.messagexform.internal.networknt")

    // SnakeYAML
    relocate("org.yaml.snakeyaml", "io.messagexform.internal.snakeyaml")
}

// Make the shadow JAR the published artifact
configurations {
    apiElements { outgoing.artifact(tasks.shadowJar) }
    runtimeElements { outgoing.artifact(tasks.shadowJar) }
}
```

### 2.2 — Update `libs.versions.toml`

```toml
[versions]
jackson = "2.18.4"    # REVERT to latest — core bundles its own
slf4j = "1.7.36"      # Downgrade — compile-time enforcement of 1.x API
```

### 2.3 — Core's Jackson version is now independent

Core uses the latest Jackson (bundled + relocated). Each gateway adapter
uses its gateway's Jackson version via `compileOnly`. No version coupling.

---

## Phase 3: Update Adapters

### 3.1 — Update `adapter-standalone`

**File:** `adapter-standalone/src/main/java/.../StandaloneAdapter.java`

The standalone adapter parses HTTP request body to `byte[]` and constructs
a `Message`:

```java
// BEFORE
JsonNode body = objectMapper.readTree(ctx.bodyInputStream());
Message msg = new Message(body, headers, ...);

// AFTER
byte[] body = ctx.bodyAsBytes();  // Javalin gives us raw bytes
Message msg = new Message(body, headers, ...);
```

And writes the response:

```java
// BEFORE
ctx.json(result.message().body());  // writes JsonNode

// AFTER
ctx.result(result.message().body()); // writes byte[]
ctx.contentType("application/json");
```

### 3.2 — Update `adapter-pingaccess` (Feature 002)

```java
// PA adapter — convert PA SDK types to core's byte[]
Exchange exchange = ...;
byte[] body = exchange.getRequest().getBody().getContent();  // raw bytes
Map<String, String> headers = extractHeaders(exchange);

// Session context: PA's JsonNode → Map<String, Object>
Map<String, Object> session = paObjectMapper.convertValue(
    identity.getAttributes(),
    new TypeReference<Map<String, Object>>() {}
);

Message msg = new Message(body, headers, ..., session);
TransformResult result = engine.transform(msg, REQUEST, ctx);

// Write back
exchange.getResponse().getBody().setContent(result.message().body());
```

### 3.3 — Update `StandaloneDependencyTest`

The allowed dependency groups will change — `com.fasterxml.jackson.core` etc.
will no longer appear in the standalone adapter's compile classpath (Jackson
is relocated inside core's shadow JAR).

---

## Phase 4: SLF4J Compile-Time Enforcement

### 4.1 — Downgrade SLF4J in `libs.versions.toml`

```toml
slf4j = "1.7.36"
```

This ensures the **compiler** prevents use of SLF4J 2.x-only APIs in core's
`src/main`. The fluent API (`LOG.atInfo()`, `LOG.atWarn()`) simply won't
compile.

### 4.2 — Test classpath keeps SLF4J 2.x

```kotlin
// core/build.gradle.kts
testImplementation("ch.qos.logback:logback-classic:1.5.6")
// Logback 1.5.x transitively brings SLF4J 2.x into the test classpath.
// Tests that use KeyValuePair or fluent API still work in tests.
```

### 4.3 — StructuredLoggingTest adjustment

`StructuredLoggingTest.java` imports `org.slf4j.event.KeyValuePair` (SLF4J
2.x-only). Since this is a **test**, it compiles against the test classpath
(SLF4J 2.x via Logback). No changes needed — tests are not deployed.

---

## Phase 5: Update Specifications

### 5.1 — Feature 001 spec updates

- **FR-001-01** (or equivalent): `TransformEngine.transform()` accepts
  `Message` with `byte[] body`, returns `TransformResult` with `byte[] body`.
- **NFR-001-xx**: Add non-functional requirement for "no third-party types
  in core's public API".

### 5.2 — Feature 002 spec updates

- **FR-002-09**: Remove "core Jackson version must match PA". Adapter uses
  PA's Jackson for SDK calls only; core is independent.
- **FR-002-06**: Session context binding uses `Map<String, Object>`, not
  `JsonNode`. PA adapter converts via `ObjectMapper.convertValue()`.

### 5.3 — Knowledge map updates

- Add ADR-0032 to relevant feature rows.

### 5.4 — SDK guide updates

- `pingaccess-sdk-guide.md`: Update §6 to show `Map<String, Object>`
  session context pattern instead of direct `JsonNode` passing.

---

## Phase 6: Cleanup

### 6.1 — Revert Jackson version in `libs.versions.toml`

Jackson reverts to latest (`2.18.4` or newer). Core bundles its own copy.

### 6.2 — Remove `net.bytebuddy` from `StandaloneDependencyTest`

Byte Buddy was added as an allowed group because Jackson 2.17.0 pulls it
transitively. With core relocating Jackson, it's no longer on the standalone
adapter's classpath.

### 6.3 — Update ADR-0031

Add a note at the top:

```markdown
> **Partially superseded by [ADR-0032](ADR-0032-core-anti-corruption-layer.md).**
> Core engine no longer compiles against PA's Jackson version. Core uses its
> own Jackson (bundled + relocated). ADR-0031 remains valid for the PA adapter's
> `compileOnly` SLF4J, Jakarta APIs, PA SDK, and the version-locked release
> strategy.
```

### 6.4 — Update PLAN.md

Add the byte-boundary refactor as a new planned task under Feature 001.

---

## File Impact Assessment

| File | Change Type | Complexity |
|------|------------|------------|
| `core/model/Message.java` | API change: `JsonNode` → `byte[]`, `Map` | Medium |
| `core/model/TransformResult.java` | API change: `JsonNode` → `byte[]` | Low |
| `core/model/TransformContext.java` | API change: remove `JsonNode`, remove helper methods | Medium |
| `core/engine/TransformEngine.java` | Internal: add parse/serialize, absorb context helpers | High |
| `core/engine/HeaderTransformer.java` | Internal: may need byte[] handling | Medium |
| `core/engine/StatusTransformer.java` | Internal: may need byte[] handling | Medium |
| `core/engine/UrlTransformer.java` | Internal: may need byte[] handling | Low |
| `core/build.gradle.kts` | Add Shadow plugin, relocation config | Medium |
| `adapter-standalone/**` | Update Message construction, response writing | Medium |
| `adapter-standalone/StandaloneDependencyTest.java` | Update allowed groups | Low |
| `core/src/test/**` | Update all test Message/TransformResult construction | High (volume) |
| `gradle/libs.versions.toml` | Revert Jackson, downgrade SLF4J | Low |
| `docs/decisions/ADR-0031-*.md` | Add supersession note | Low |
| `docs/decisions/ADR-0032-*.md` | Already created | Done |
| `docs/architecture/features/001/spec.md` | Add byte-boundary requirements | Medium |
| `docs/architecture/features/002/spec.md` | Remove Jackson version coupling | Medium |
| `PLAN.md` | Add refactor task | Low |

**Estimated files changed:** 20-30
**Estimated effort:** 2-3 focused sessions

---

## Verification Checklist

- [ ] Core's public API (`Message`, `TransformResult`, `TransformContext`)
      has zero Jackson imports
- [ ] Core's shadow JAR contains relocated Jackson classes under
      `io.messagexform.internal.jackson`
- [ ] `./gradlew :core:dependencies --configuration apiElements` shows NO
      Jackson in the API configuration
- [ ] `./gradlew :adapter-standalone:dependencies` shows NO
      `com.fasterxml.jackson` (all relocated inside core's JAR)
- [ ] All existing tests pass
- [ ] `libs.versions.toml` has Jackson at latest (e.g., 2.18.4)
- [ ] SLF4J compiles at 1.7.x — `LOG.atInfo()` fails to compile in core src/main
- [ ] Shadow JAR size < 5 MB (NFR-002-02)
- [ ] Performance benchmark: < 1ms overhead for parse/serialize at boundary

---

*Implementation order: Phase 1 → Phase 4 → Phase 2 → Phase 3 → Phase 5 → Phase 6*

*Phase 1 (API change) and Phase 4 (SLF4J) can be done first because they
don't require the Shadow plugin. Phase 2 (relocation) is the build system
change. Phase 3 (adapters) follows naturally. Phase 5 and 6 are documentation
and cleanup.*

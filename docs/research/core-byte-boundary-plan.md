# Core Byte-Boundary Refactor — Implementation Plan

Status: **📋 Planned** | Created: 2026-02-11 | ADRs: 0032, 0033

> **Goal:** Remove all third-party types (Jackson, SLF4J 2.x-only APIs) from
> core's public API. Core defines its own port value objects (`MessageBody`,
> `HttpHeaders`, `SessionContext`) that accept only plain Java types. Gateway
> adapters convert between their SDK-specific types and core's opaque port
> boundary. Core bundles and relocates its own Jackson internally.

## Architecture Before / After

### Before (ADR-0031 — current)

```
Adapter  ──JsonNode──►  Core Engine  ──JsonNode──►  Adapter
           shared                        shared

Message(JsonNode body, Map<String,String> headers, JsonNode sessionContext)
         ^^^^^^^                                    ^^^^^^^^
         Jackson type (shared boundary)             Jackson type
```

Jackson `JsonNode` is a **shared boundary type**. All adapters and core
MUST use the same Jackson version at runtime.

### After (ADR-0032 + ADR-0033 — target)

```
Adapter  ──MessageBody──►  Core Engine  ──MessageBody──►  Adapter
           (byte[] inside)  (own Jackson,   (byte[] inside)
                             relocated)

Message(MessageBody body, HttpHeaders headers, SessionContext session)
        ^^^^^^^^^^^       ^^^^^^^^^^           ^^^^^^^^^^^^^^
        core-owned        core-owned           core-owned
        (zero deps)       (zero deps)          (zero deps)
```

Core hides Jackson behind port value objects. Each adapter uses its
gateway's Jackson (or no Jackson at all) independently.

---

## Phase 1: Create Port Value Objects (Feature 001)

> **Scope:** New files in `core/src/main/java/io/messagexform/core/model/`.
> Non-breaking — old types remain until Phase 2.

### 1.1 — Create `MediaType` enum

**File:** `core/src/main/java/io/messagexform/core/model/MediaType.java`

```java
public enum MediaType {
    JSON("application/json"),
    XML("application/xml"),
    FORM("application/x-www-form-urlencoded"),
    TEXT("text/plain"),
    BINARY("application/octet-stream"),
    NONE(null);

    public String value() { ... }
    public static MediaType fromContentType(String contentType) { ... }
}
```

**Dependency:** None (pure Java enum).

### 1.2 — Create `MessageBody` record

**File:** `core/src/main/java/io/messagexform/core/model/MessageBody.java`

```java
public record MessageBody(byte[] content, MediaType mediaType) {
    public boolean isEmpty() { ... }
    public String asString() { ... }
    public int size() { ... }

    // Factory methods
    public static MessageBody json(byte[] content) { ... }
    public static MessageBody json(String content) { ... }
    public static MessageBody empty() { ... }
    public static MessageBody of(byte[] content, MediaType mediaType) { ... }
}
```

**Key design:** Custom `equals`/`hashCode` that uses `Arrays.equals()` for
the `byte[]` field (records don't do this by default).

### 1.3 — Create `HttpHeaders` class

**File:** `core/src/main/java/io/messagexform/core/model/HttpHeaders.java`

```java
public final class HttpHeaders {
    public String first(String name) { ... }       // case-insensitive
    public List<String> all(String name) { ... }   // all values
    public boolean contains(String name) { ... }
    public Map<String, String> toSingleValueMap() { ... }
    public Map<String, List<String>> toMultiValueMap() { ... }

    // Factory methods
    public static HttpHeaders of(Map<String, String> singleValue) { ... }
    public static HttpHeaders ofMulti(Map<String, List<String>> multiValue) { ... }
    public static HttpHeaders empty() { ... }
}
```

**Key design:** All keys normalized to lowercase in constructors. Internal
storage uses `TreeMap` for consistent ordering.

### 1.4 — Create `SessionContext` class

**File:** `core/src/main/java/io/messagexform/core/model/SessionContext.java`

```java
public final class SessionContext {
    public Object get(String key) { ... }
    public String getString(String key) { ... }
    public boolean has(String key) { ... }
    public boolean isEmpty() { ... }
    public Map<String, Object> toMap() { ... }

    // Factory methods
    public static SessionContext of(Map<String, Object> attributes) { ... }
    public static SessionContext empty() { ... }
}
```

**Key design:** `toString()` prints only key names (not values) to avoid
leaking sensitive session data in logs.

### 1.5 — Write unit tests for all port types

**Files:**
- `core/src/test/java/io/messagexform/core/model/MessageBodyTest.java`
- `core/src/test/java/io/messagexform/core/model/HttpHeadersTest.java`
- `core/src/test/java/io/messagexform/core/model/SessionContextTest.java`
- `core/src/test/java/io/messagexform/core/model/MediaTypeTest.java`

Test coverage:
- Factory methods produce correct instances
- `HttpHeaders` is case-insensitive
- `MessageBody.equals()` uses `Arrays.equals()` on byte[]
- `SessionContext.empty()` singleton behavior
- Null-safety: all factories handle null gracefully

---

## Phase 2: Migrate Core API to Port Types

> **Scope:** Modify existing `Message`, `TransformResult`, `TransformContext`
> to use the new port types. This is the breaking change.

### 2.1 — Rewrite `Message.java`

**Before:**
```java
public record Message(
    JsonNode body,
    Map<String, String> headers,
    Map<String, List<String>> headersAll,
    Integer statusCode,
    String contentType,
    String requestPath,
    String requestMethod,
    String queryString,
    JsonNode sessionContext
)
```

**After:**
```java
public record Message(
    MessageBody body,
    HttpHeaders headers,
    Integer statusCode,
    String requestPath,
    String requestMethod,
    String queryString,
    SessionContext session
) {
    public Message withBody(MessageBody newBody) { ... }
    public Message withHeaders(HttpHeaders newHeaders) { ... }
    public Message withStatusCode(Integer newStatusCode) { ... }
    public MediaType mediaType() { return body.mediaType(); }
    public String contentType() { return body.mediaType().value(); }
}
```

**Fields removed:** `headersAll` (merged into `HttpHeaders`), `contentType`
(derived from `MessageBody.mediaType()`).

**Fields renamed:** `sessionContext` → `session` (cleaner).

### 2.2 — Rewrite `TransformResult.java`

**Before:**
```java
private final JsonNode errorResponse;
public static TransformResult error(JsonNode errorResponse, int statusCode) { ... }
public JsonNode errorResponse() { ... }
```

**After:**
```java
private final MessageBody errorResponse;
public static TransformResult error(MessageBody errorBody, int statusCode) { ... }
public MessageBody errorResponse() { ... }
```

### 2.3 — Rewrite `TransformContext.java`

**Before:**
```java
public record TransformContext(
    Map<String, String> headers,
    Map<String, List<String>> headersAll,
    Integer status,
    Map<String, String> queryParams,
    Map<String, String> cookies,
    JsonNode sessionContext
) {
    public JsonNode headersAsJson() { ... }
    public JsonNode statusAsJson() { ... }
    public JsonNode queryParamsAsJson() { ... }
    public JsonNode cookiesAsJson() { ... }
    public JsonNode sessionContextAsJson() { ... }
}
```

**After:**
```java
public record TransformContext(
    HttpHeaders headers,
    Integer status,
    Map<String, String> queryParams,
    Map<String, String> cookies,
    SessionContext session
) {
    public static TransformContext empty() { ... }
}
```

**Removed:** All `*AsJson()` methods — these move to `TransformEngine`
as internal conversion logic (they need Jackson, which is now internal).

### 2.4 — Update `TransformEngine.java` internals

The engine absorbs the context-to-JsonNode conversion methods:

```java
// INTERNAL — uses core's relocated Jackson
private JsonNode toJsonNode(MessageBody body) {
    if (body.isEmpty()) return NullNode.getInstance();
    return internalMapper.readTree(body.content());
}

private JsonNode toJsonNode(HttpHeaders headers) {
    ObjectNode node = internalMapper.createObjectNode();
    headers.toSingleValueMap().forEach(node::put);
    return node;
}

private JsonNode toJsonNode(SessionContext session) {
    if (session.isEmpty()) return NullNode.getInstance();
    return internalMapper.valueToTree(session.toMap());
}

private MessageBody toMessageBody(JsonNode node) {
    return MessageBody.json(internalMapper.writeValueAsBytes(node));
}
```

### 2.5 — Update `HeaderTransformer`, `StatusTransformer`, `UrlTransformer`

These transformers currently work with `JsonNode` / `Map` internally. They
need to accept the new port types and perform internal conversion.

### 2.6 — Update all core tests

Every test that constructs a `Message` changes from:

```java
// BEFORE
JsonNode body = objectMapper.readTree("{\"key\": \"value\"}");
Message msg = new Message(body, headers, headersAll, 200, "application/json", "/path", "GET");

// AFTER
Message msg = new Message(
    MessageBody.json("{\"key\": \"value\"}"),
    HttpHeaders.of(Map.of("content-type", "application/json")),
    200, "/path", "GET", null, SessionContext.empty()
);
```

**Estimated test files impacted:** 15-20 (all test classes that construct
`Message` or check `TransformResult`).

---

## Phase 3: Jackson Relocation in Core (Build System)

> **Scope:** Build configuration changes to bundle and relocate Jackson
> inside core's shadow JAR.

### 3.1 — Add Shadow plugin to core module

**File:** `core/build.gradle.kts`

```kotlin
plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

dependencies {
    // Jackson — bundled and relocated (internal only)
    implementation(libs.jackson.databind)

    // JSLT — bundled and relocated (depends on Jackson)
    implementation(libs.jslt)

    // JSON Schema Validator — bundled and relocated
    implementation(libs.json.schema.validator)

    // SnakeYAML — bundled and relocated
    implementation(libs.snakeyaml)

    // SLF4J API — NOT bundled (gateway-provided or standalone-provided)
    compileOnly(libs.slf4j.api)
}

shadowJar {
    archiveClassifier.set("")  // replace main JAR

    // Relocate all internal dependencies
    relocate("com.fasterxml.jackson", "io.messagexform.internal.jackson")
    relocate("com.schibsted.spt.data.jslt", "io.messagexform.internal.jslt")
    relocate("com.networknt", "io.messagexform.internal.networknt")
    relocate("org.yaml.snakeyaml", "io.messagexform.internal.snakeyaml")

    // Exclude SLF4J — provided by gateway or standalone
    exclude("org/slf4j/**")
}

// Publish shadow JAR as the main artifact
configurations {
    apiElements { outgoing.artifact(tasks.shadowJar) }
    runtimeElements { outgoing.artifact(tasks.shadowJar) }
}
```

### 3.2 — Update `libs.versions.toml`

```toml
[versions]
jackson = "2.18.4"    # REVERT to latest — core bundles its own
slf4j = "1.7.36"      # Compile against 1.x — prevents 2.x-only API usage
```

### 3.3 — Verify relocation

```bash
# Check that core's JAR contains relocated Jackson
jar tf core/build/libs/core-*.jar | grep io/messagexform/internal/jackson

# Check that core's JAR does NOT contain unrelocated Jackson
jar tf core/build/libs/core-*.jar | grep com/fasterxml/jackson
# expected: no output
```

---

## Phase 4: SLF4J Compile-Time Enforcement

### 4.1 — Downgrade SLF4J in `libs.versions.toml`

```toml
slf4j = "1.7.36"
```

Core compiles against SLF4J 1.7.x API. The compiler prevents use of 2.x-only
methods (`LOG.atInfo()`, `LOG.atWarn()`, `LOG.isEnabledForLevel()`).

### 4.2 — Test classpath keeps SLF4J 2.x

```kotlin
// core/build.gradle.kts
testImplementation("ch.qos.logback:logback-classic:1.5.6")
// Logback 1.5.x transitively brings SLF4J 2.x into test classpath.
// Tests that use KeyValuePair or fluent API still compile.
```

### 4.3 — `StructuredLoggingTest` adjustment

This test imports `org.slf4j.event.KeyValuePair` (SLF4J 2.x). It compiles
against the test classpath (SLF4J 2.x via Logback). No changes needed.

---

## Phase 5: Update Adapters

### 5.1 — Update `adapter-standalone`

```java
// BEFORE
JsonNode body = objectMapper.readTree(ctx.bodyInputStream());
Message msg = new Message(body, headerMap, headerMapAll, null, contentType, ...);

// AFTER
Message msg = new Message(
    MessageBody.json(ctx.bodyAsBytes()),
    HttpHeaders.of(ctx.headerMap()),
    null,
    ctx.path(),
    ctx.method().name(),
    ctx.queryString(),
    SessionContext.empty()
);
```

Response writing:
```java
// BEFORE
ctx.json(result.message().body());  // writes JsonNode directly

// AFTER
ctx.result(result.message().body().content());  // writes raw bytes
ctx.contentType(result.message().contentType());
```

### 5.2 — Update `adapter-pingaccess` (Feature 002)

```java
// PA adapter
byte[] body = exchange.getRequest().getBody().getContent();
Map<String, String> headers = extractHeaders(exchange);

// Convert PA's JsonNode → Map using PA's own ObjectMapper
Map<String, Object> sessionAttrs = paObjectMapper.convertValue(
    identity.getAttributes(), new TypeReference<Map<String, Object>>() {});

Message msg = new Message(
    MessageBody.json(body),
    HttpHeaders.of(headers),
    null,
    exchange.getRequest().getUrl().getPath(),
    exchange.getRequest().getMethod(),
    exchange.getRequest().getUrl().getQuery(),
    SessionContext.of(sessionAttrs)
);

TransformResult result = engine.transform(msg, Direction.REQUEST);

if (result.isSuccess()) {
    exchange.getRequest().getBody()
        .setContent(result.message().body().content());
}
```

### 5.3 — Update `StandaloneDependencyTest`

Jackson groups (`com.fasterxml.jackson.core`, etc.) will no longer appear in
standalone's compile classpath — they're relocated inside core's shadow JAR.
Update allowed groups accordingly.

---

## Phase 6: Update Specifications and Documentation

### 6.1 — Feature 001 spec

- Add non-functional requirement: "Core's public API must not reference any
  third-party types. All port types are core-owned value objects
  (ADR-0032, ADR-0033)."
- Update `Message`, `TransformResult`, `TransformContext` definitions.

### 6.2 — Feature 002 spec

- Remove Jackson version coupling constraints.
- Update session context binding to use `SessionContext.of(Map)`.
- Update FR-002-06 to reflect `MessageBody` boundary.

### 6.3 — Knowledge map

- Add ADR-0032 and ADR-0033 to relevant feature rows.

### 6.4 — SDK guide

- `pingaccess-sdk-guide.md`: Update section on session context to show
  `Map<String, Object>` conversion pattern.

---

## Phase 7: Cleanup

### 7.1 — Revert Jackson version

`libs.versions.toml` Jackson reverts to latest (e.g., `2.18.4`).

### 7.2 — Remove `net.bytebuddy` from `StandaloneDependencyTest`

No longer needed — Jackson is relocated inside core.

### 7.3 — Update ADR-0031

Supersession notes already added. Verify accuracy after refactor.

### 7.4 — Update PLAN.md

Mark "Core byte-boundary refactor" as complete.

---

## File Impact Assessment

### New Files (Phase 1)

| File | Description |
|------|-------------|
| `core/src/main/java/.../model/MediaType.java` | Content type enum |
| `core/src/main/java/.../model/MessageBody.java` | Body value object |
| `core/src/main/java/.../model/HttpHeaders.java` | Header collection |
| `core/src/main/java/.../model/SessionContext.java` | Session value object |
| `core/src/test/java/.../model/MediaTypeTest.java` | Tests |
| `core/src/test/java/.../model/MessageBodyTest.java` | Tests |
| `core/src/test/java/.../model/HttpHeadersTest.java` | Tests |
| `core/src/test/java/.../model/SessionContextTest.java` | Tests |

### Modified Files (Phases 2-7)

| File | Change | Complexity |
|------|--------|------------|
| `core/model/Message.java` | Full rewrite: port types | Medium |
| `core/model/TransformResult.java` | `JsonNode` → `MessageBody` | Low |
| `core/model/TransformContext.java` | Full rewrite: port types, remove *AsJson() | Medium |
| `core/engine/TransformEngine.java` | Absorb *AsJson(), add parse/serialize | High |
| `core/engine/HeaderTransformer.java` | Port type adaptation | Medium |
| `core/engine/StatusTransformer.java` | Port type adaptation | Medium |
| `core/engine/UrlTransformer.java` | Port type adaptation | Low |
| `core/engine/ErrorResponseBuilder.java` | `JsonNode` → `MessageBody` | Low |
| `core/build.gradle.kts` | Shadow plugin, relocation | Medium |
| `adapter-standalone/**` | Message construction, response writing | Medium |
| `adapter-standalone/StandaloneDependencyTest.java` | Update allowed groups | Low |
| `core/src/test/**` (~15-20 files) | Update Message/TransformResult construction | High (volume) |
| `gradle/libs.versions.toml` | Revert Jackson, downgrade SLF4J | Low |
| `docs/decisions/ADR-0031-*.md` | Verify supersession notes | Low |
| `docs/architecture/features/001/spec.md` | Port type requirements | Medium |
| `docs/architecture/features/002/spec.md` | Remove Jackson coupling | Medium |
| `PLAN.md` | Mark complete | Low |
| `llms.txt` | Add ADR-0033 | Low |

**Estimated files changed:** 25-35
**Estimated effort:** 3-4 focused sessions

---

## Execution Order

```
Phase 1 ──► Phase 2 ──► Phase 4 ──► Phase 3 ──► Phase 5 ──► Phase 6 ──► Phase 7
(new types)  (migrate)   (SLF4J)    (shadow)    (adapters)  (specs)     (cleanup)
```

**Rationale:**
1. **Phase 1** (new types) is additive — creates files, breaks nothing.
2. **Phase 2** (migrate API) is the big break — changes core's contract.
3. **Phase 4** (SLF4J) can be done alongside Phase 2 — independent change.
4. **Phase 3** (shadow/relocate) builds on Phase 2 — needs the new API first.
5. **Phase 5** (adapters) adapts to the new core API.
6. **Phase 6** (specs) documents the completed state.
7. **Phase 7** (cleanup) finalizes versions and markers.

---

## Verification Checklist

- [ ] All 4 port types created with full test coverage
- [ ] Core's public API (`Message`, `TransformResult`, `TransformContext`)
      has **zero** Jackson imports
- [ ] Core's shadow JAR contains relocated Jackson classes under
      `io.messagexform.internal.jackson`
- [ ] `./gradlew :core:dependencies --configuration apiElements` shows NO
      Jackson in the API configuration
- [ ] `./gradlew :adapter-standalone:dependencies` shows NO
      `com.fasterxml.jackson` (all relocated inside core's JAR)
- [ ] All existing tests pass (258+)
- [ ] `libs.versions.toml` has Jackson at latest (e.g., 2.18.4)
- [ ] SLF4J compiles at 1.7.x — `LOG.atInfo()` fails to compile in
      core src/main
- [ ] New port type tests pass
- [ ] Shadow JAR size < 5 MB
- [ ] Performance benchmark: < 1ms overhead for parse/serialize at boundary
- [ ] `MessageBody.equals()` uses `Arrays.equals()` (not reference equality)
- [ ] `HttpHeaders.of(Map.of("Content-Type", "json")).first("content-type")`
      returns `"json"` (case-insensitive verified)

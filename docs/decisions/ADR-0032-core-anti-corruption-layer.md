# ADR-0032 – Core Anti-Corruption Layer (Byte Boundary)

Date: 2026-02-11 | Status: Accepted

Supersedes: ADR-0031 (partially — Jackson version coupling for core is eliminated;
ADR-0031 remains valid for PA adapter's `compileOnly` SLF4J, Jakarta, and PA SDK)

## Context

### The Multi-Gateway Problem

The message-xform system is architected as a **core engine + pluggable
gateway adapters**:

```
                    core engine
                        │
       ┌────────────────┼────────────────┐────────────┐
       │                │                │            │
   adapter-         adapter-         adapter-     adapter-
   standalone       pingaccess       pinggateway   kong
```

Each gateway ships its own versions of shared libraries — notably Jackson:

| Gateway              | Ships Jackson | Ships SLF4J |
|----------------------|---------------|-------------|
| PingAccess 9.0       | 2.17.0        | 1.7.36      |
| PingGateway 2024     | TBD           | TBD         |
| Kong (Java plugin)   | TBD           | TBD         |
| Standalone           | latest        | latest      |

Under ADR-0031, core's public API directly exposes Jackson types:

```java
public record Message(JsonNode body, ...)       // Jackson type in API
public record TransformContext(JsonNode sessionContext, ...) // Jackson type
public class TransformResult { JsonNode errorResponse(); }  // Jackson type
```

This creates a **version coupling**: core must compile against a specific
Jackson version, and every gateway adapter shares that exact version at
runtime. Consequences:

1. **Lowest common denominator** — core locked to the oldest Jackson shipped
   by any supported gateway.
2. **Security vulnerability exposure** — if a gateway ships a vulnerable
   Jackson, core cannot independently patch it.
3. **Cascade upgrades** — a Jackson security fix in the standalone adapter
   requires rebuilding and retesting all gateway plugins.
4. **New gateway onboarding** — adding a gateway that ships a different
   Jackson version requires version negotiation across all adapters.

### The Pattern

This problem is well-known in software architecture, with established
solutions:

- **Anti-Corruption Layer (ACL)** — Eric Evans, *Domain-Driven Design*
  (2003). An isolation layer that prevents external models from corrupting
  the internal domain.
- **Hexagonal Architecture / Ports and Adapters** — Alistair Cockburn (2005).
  The core defines "ports" using its own types; "adapters" translate between
  external and internal representations.
- **Serialization Boundary** — using `byte[]` as the interchange format
  between subsystems with incompatible type systems.

### Shared vs. Not-Shared Dependencies

The key distinction is not *what* a dependency is, but whether its types are
*shared* across the core–adapter boundary:

| Dependency | Core uses internally? | Gateway SDK exposes? | Shared? | Strategy |
|-----------|----------------------|---------------------|---------|----------|
| Jackson   | Yes (JSLT engine)    | Yes (e.g. PA `Identity.getAttributes()`) | **YES** | HIDE behind byte[] |
| SLF4J     | Yes (logging)        | Yes (gateway classpath) | **YES** | HIDE — core uses relocated or minimal API |
| JSLT      | Yes                  | No                  | No      | Bundle freely |
| SnakeYAML | Yes (YAML parsing)   | No                  | No      | Bundle freely |
| JSON Schema Val. | Yes           | No                  | No      | Bundle freely |

**Rule: shared dependencies must never appear in core's public API.** Core
internalizes them (bundles + relocates), preventing version coupling with
any gateway.

### Options Considered

- **Option A — Byte boundary + Jackson relocation in core** (chosen)
  - Core's public API uses only `byte[]`, `Map<String, String>`,
    `Map<String, Object>`, and other plain Java types.
  - Core bundles and relocates its own Jackson
    (`com.fasterxml.jackson` → `io.messagexform.internal.jackson`).
  - Gateway adapters convert between their SDK types and core's opaque
    boundary.
  - Pros: complete version decoupling, independent security patching,
    multi-gateway support, clean separation of concerns.
  - Cons: refactor cost (core API changes, all adapters updated);
    small performance overhead (one extra serialize/deserialize at boundary).

- **Option B — Keep Jackson in core's API** (rejected — ADR-0031 status quo)
  - Core continues exposing `JsonNode` in `Message`, `TransformResult`,
    `TransformContext`.
  - Each gateway adapter must use exactly the Jackson version core picked.
  - Rejected: breaks multi-gateway architecture, creates version coupling,
    prevents independent security patching.

- **Option C — Core defines custom tree model** (rejected)
  - Replace `JsonNode` with a core-defined `MessageNode` abstraction.
  - Rejected: reinvents Jackson's tree model poorly; JSLT still needs
    Jackson's `JsonNode` internally, so conversion is unavoidable.

## Decision

We adopt **Option A — byte boundary with Jackson relocation in core**.

### Core Engine API (after refactor)

```java
// Core input — no Jackson types
public record Message(
    byte[] body,                      // raw JSON bytes (not JsonNode)
    Map<String, String> headers,
    Map<String, List<String>> headersAll,
    Integer statusCode,
    String contentType,
    String requestPath,
    String requestMethod,
    String queryString,
    Map<String, Object> sessionContext // plain Java types (not JsonNode)
)

// Core output — no Jackson types
public final class TransformResult {
    public enum Type { SUCCESS, ERROR, PASSTHROUGH }

    public Message message();         // transformed message with byte[] body
    public byte[] errorResponse();    // RFC 9457 error as bytes (not JsonNode)
    public Integer errorStatusCode();
}
```

### Internal Processing (hidden from adapters)

```java
// Inside TransformEngine (internal — adapters never see this)
public TransformResult transform(Message message, Direction direction, TransformContext ctx) {
    // 1. Parse byte[] → JsonNode using core's relocated Jackson
    JsonNode bodyNode = internalMapper.readTree(message.body());

    // 2. Run JSLT transform (uses core's relocated Jackson)
    JsonNode result = jsltTransform.apply(bodyNode);

    // 3. Serialize back to byte[]
    byte[] outputBytes = internalMapper.writeValueAsBytes(result);

    return TransformResult.success(message.withBody(outputBytes));
}
```

### Shadow JAR Configuration

```kotlin
// core/build.gradle.kts
plugins {
    id("com.gradleup.shadow")
}

shadowJar {
    // Relocate Jackson — invisible to adapters
    relocate("com.fasterxml.jackson", "io.messagexform.internal.jackson")

    // Also relocate JSLT (it depends on Jackson)
    relocate("com.schibsted.spt.data.jslt", "io.messagexform.internal.jslt")
}
```

### Gateway Adapter Pattern

Each adapter follows the same pattern — extract raw data from the gateway's
SDK types, call core, write raw data back:

```java
// Generic adapter pattern (pseudocode)
class GatewayAdapter {
    TransformEngine engine; // core engine

    void handleRequest(GatewayRequest req) {
        // 1. Extract raw data (gateway-specific)
        byte[] body = req.getBodyAsBytes();
        Map<String, String> headers = req.getHeaders();
        Map<String, Object> session = extractSession(req); // gateway-specific

        // 2. Call core (gateway-agnostic)
        Message msg = new Message(body, headers, ...);
        TransformResult result = engine.transform(msg, REQUEST);

        // 3. Write back (gateway-specific)
        req.setBody(result.message().body());
        req.setHeaders(result.message().headers());
    }
}
```

For PingAccess, the session context conversion is:

```java
// PA adapter — convert PA's JsonNode to Map<String, Object>
Map<String, Object> session = paObjectMapper.convertValue(
    identity.getAttributes(),
    new TypeReference<Map<String, Object>>() {}
);
```

This uses **PA's own ObjectMapper** (PA's Jackson version) to convert PA's
`JsonNode` to plain Java types. Core never sees PA's `JsonNode`.

### SLF4J Strategy

Core compiles against SLF4J **1.7.36** (the lowest version shipped by any
supported gateway). At this version, the compiler prevents use of 2.x-only
APIs (fluent logging). The standalone adapter bundles SLF4J 2.x + Logback
for its own code and test suite — core's compiled bytecode is binary
compatible with both 1.x and 2.x runtimes.

### What ADR-0031 Still Covers

ADR-0031 remains **valid** for:
- PA adapter's `compileOnly` declarations (SLF4J, Jakarta APIs, PA SDK)
- PA's flat classpath model (verified)
- Version-locked release strategy (adapter version tracks PA version)
- Runtime version guard (warns on PA dependency drift)
- Script-based dependency extraction (`scripts/pa-extract-deps.sh`)


## Consequences

Positive:
- **Multi-gateway support** — each adapter handles its gateway's types
  independently. Adding a new gateway never affects core or other adapters.
- **Security independence** — core always uses the latest Jackson (fully
  patched). Gateway vulnerabilities are the gateway vendor's problem.
- **No version coupling** — standalone adapter upgrades freely.
- **Compile-time safety** — core's `public` API has no third-party types that
  can leak across boundaries. Contributors cannot accidentally introduce
  version-specific code in the wrong layer.
- **Clean adapter pattern** — every adapter follows the same
  "extract bytes → call core → write bytes" template.
- **SLF4J enforcement** — compiling against 1.7.x prevents 2.x-only API
  usage at compile time.

Negative / trade-offs:
- **Refactor cost** — core's `Message`, `TransformResult`, `TransformContext`
  all change from `JsonNode` to `byte[]`/`Map`. All tests and adapters
  must be updated. Estimated: 15-25 files.
- **Performance overhead** — one extra `byte[] → JsonNode` parse and
  `JsonNode → byte[]` serialize at the boundary per transform. For typical
  payloads (< 100 KB), this adds <1ms. Negligible compared to the JSLT
  transform itself.
- **Shadow JAR complexity** — core must relocate Jackson and JSLT. The
  `build.gradle.kts` configuration is more involved.
- **Debugging** — stack traces show relocated package names
  (`io.messagexform.internal.jackson`). Mitigated by clear naming convention.


References:
- ADR-0031: PA-Provided Dependencies
- ADR-0025: Adapter Lifecycle SPI
- ADR-0030: Session Context Binding
- Evans, E. (2003). *Domain-Driven Design*. Addison-Wesley. Ch. 14:
  "Anticorruption Layer"
- Cockburn, A. (2005). *Hexagonal Architecture* (Ports and Adapters)

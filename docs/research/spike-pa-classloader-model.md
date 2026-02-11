# Spike A: PingAccess Classloader Model Discovery

Status: **🔲 Not Started** | Created: 2026-02-11 | Feature: 002

## Tracker

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| A-1 | Create diagnostic plugin skeleton | 🔲 | Minimal `RuleInterceptor` |
| A-2 | Implement classloader inspection in `configure()` | 🔲 | Log chain, visibility, identity |
| A-3 | Implement runtime class identity tests | 🔲 | Same-class checks for Jackson types |
| A-4 | Build diagnostic shadow JAR (minimal) | 🔲 | Only adapter + SDK, no relocation |
| A-5 | Deploy to PA 9.0 Docker container | 🔲 | Mount JAR to `/opt/server/deploy/` |
| A-6 | Collect and analyze logs | 🔲 | Extract classloader chain, visibility map |
| A-7 | Document findings in SDK guide §9 | 🔲 | New "Classloader Model" subsection |
| A-8 | Draft ADR on dependency strategy | 🔲 | ADR-0031 or next available |
| A-9 | Determine spec impact | 🔲 | List of spec sections to amend |

---

## Motivation

### The Problem

Feature 002 (PingAccess Adapter) currently specifies that Jackson MUST be
**relocated** (shaded) in the shadow JAR to avoid `ClassCastException` when
PA's Jackson classes and the adapter's Jackson classes coexist. This decision
was made defensively because:

1. **PA's classloading model is not documented** in the SDK or any public Ping
   Identity resource.
2. We assumed PA might use classloader isolation that would cause PA's
   `JsonNode` and our bundled `JsonNode` to be different `Class` objects.

### Why This Matters

Jackson relocation introduces significant complexity:

| Cost | Impact |
|------|--------|
| Dual `ObjectMapper` instances | One for shaded Jackson (adapter internal), one for PA-native Jackson (boundary conversion). Confusing and error-prone. |
| Boundary conversion | Every `JsonNode` crossing the PA↔adapter boundary must be serialized to `byte[]` and deserialized back. Extra allocation + latency. |
| Compile-time ≠ runtime class names | `com.fasterxml.jackson.databind.ObjectMapper` at compile time becomes `io.messagexform.shaded.jackson.databind.ObjectMapper` in the JAR. Stack traces are harder to read. |
| Increased JAR size | Bundled Jackson adds ~2-3 MB to the shadow JAR. |
| Code complexity | The `buildSessionContext()` method (FR-002-06) requires boundary conversion for `Identity.getAttributes()` and `SessionStateSupport.getAttributes()` — 4 extra code paths. |

### The Hypothesis

The PingAccess SDK API **directly exposes Jackson types** in its public
interfaces:

- `Identity.getAttributes()` → returns `JsonNode`
- `SessionStateSupport.setAttribute(String, JsonNode)` → takes `JsonNode`
- `PluginConfiguration` uses `@JsonProperty`, `@JsonUnwrapped`
- PA's `configure()` step does Jackson deserialization of the config JSON
  into the plugin's config class

This strongly implies that **PA expects plugins to share its Jackson instance**.
If PA intended plugins to shade Jackson, it would not expose `JsonNode` in
its public API — it would use `byte[]` or `String` at the boundary.

### Goal

Deploy a minimal diagnostic plugin to PA 9.0 Docker and **definitively
answer** these questions:

1. What classloader type does PA use for plugin JARs in `/deploy/`?
2. What is the parent classloader chain?
3. Can plugin code see PA's internal libraries (Jackson, SnakeYAML, SLF4J, etc.)?
4. Is `JsonNode` loaded by the plugin the **same `Class` object** as `JsonNode`
   used by PA's `Identity.getAttributes()`?
5. What is the delegation model — parent-first or child-first?

---

## Background & Context

### Relevant Files

| File | Purpose |
|------|---------|
| `docs/architecture/features/002/spec.md` | Feature 002 spec — FR-002-09 (Deployment Packaging) mandates Jackson relocation |
| `docs/architecture/features/002/pingaccess-sdk-guide.md` §9 | Current classloading documentation (assumption-based) |
| `docs/architecture/features/002/pingaccess-sdk-guide.md` §6 | Boundary conversion pattern (would be eliminated if Jackson is shared) |
| `docs/research/pingaccess-docker-and-sdk.md` | Docker image details, SDK extraction |
| `docs/research/pingaccess-plugin-api.md` | Plugin API analysis |
| `docs/reference/pingaccess-sdk/pingaccess-sdk-9.0.1.0.jar` | SDK JAR for compilation |

### SDK Evidence That Jackson Is Shared

From decompiled SDK bytecode (confirmed in SDK guide):

```java
// com.pingidentity.pa.sdk.policy.exchangedata.Identity
public interface Identity {
    JsonNode getAttributes();  // returns com.fasterxml.jackson.databind.JsonNode
    // ...
}

// com.pingidentity.pa.sdk.policy.exchangedata.SessionStateSupport
public interface SessionStateSupport {
    void setAttribute(String key, JsonNode value);  // takes JsonNode
    Map<String, JsonNode> getAttributes();           // returns Map of JsonNode
}
```

The SDK `pingaccess-sdk-9.0.1.0.jar` declares Jackson as a dependency
(`jackson-databind` appears in the SDK POM). The SDK samples (in
`.sdk-decompile/pingaccess-9.0.1/sdk/`) use `ObjectMapper` and `JsonNode`
freely — none of them relocate Jackson.

### Docker Image Details

| Property | Value |
|----------|-------|
| Image | `pingidentity/pingaccess:9.0.1-latest` |
| PA home | `/opt/server/` |
| Plugin deploy dir | `/opt/server/deploy/` |
| Platform libs | `/opt/server/lib/` |
| Log dir | `/opt/out/instance/log/` |
| Admin port | 9000 (HTTPS) |
| Engine port | 3000 |

---

## Execution Plan

### A-1: Create Diagnostic Plugin Skeleton

Create a minimal Gradle module (can be temporary, outside the main build, or
in a scratch directory) with:

- A single class: `ClassloaderDiagnosticRule` extending
  `AsyncRuleInterceptorBase`
- `@Rule(type = "ClassloaderDiagnostic", configClass = ...)`
- A minimal `PluginConfiguration` implementation with no fields
- `META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor`

Dependencies:
```kotlin
compileOnly("com.pingidentity.pingaccess:pingaccess-sdk:9.0.1.0")
// OR: compileOnly(files("docs/reference/pingaccess-sdk/pingaccess-sdk-9.0.1.0.jar"))
```

**No Jackson dependency declared** — we want to see if PA's Jackson is visible.

### A-2: Implement Classloader Inspection

In `configure(T config)`, add the following diagnostics:

```java
private void inspectClassloader() {
    Logger log = LoggerFactory.getLogger(ClassloaderDiagnosticRule.class);

    // 1. Report the plugin's own classloader
    ClassLoader cl = this.getClass().getClassLoader();
    log.info("[DIAG] Plugin classloader: {} (identity: {})",
             cl.getClass().getName(), System.identityHashCode(cl));

    // 2. Walk the parent chain
    ClassLoader parent = cl.getParent();
    int depth = 0;
    while (parent != null) {
        log.info("[DIAG]   Parent[{}]: {} (identity: {})",
                 depth++, parent.getClass().getName(),
                 System.identityHashCode(parent));

        // If URLClassLoader, list URLs (JAR paths)
        if (parent instanceof java.net.URLClassLoader ucl) {
            for (java.net.URL url : ucl.getURLs()) {
                log.info("[DIAG]     URL: {}", url);
            }
        }
        parent = parent.getParent();
    }

    // 3. Check if plugin's own classloader is a URLClassLoader
    if (cl instanceof java.net.URLClassLoader ucl) {
        log.info("[DIAG] Plugin classloader URLs:");
        for (java.net.URL url : ucl.getURLs()) {
            log.info("[DIAG]   URL: {}", url);
        }
    }

    // 4. Report thread context classloader
    ClassLoader tccl = Thread.currentThread().getContextClassLoader();
    log.info("[DIAG] Thread context classloader: {} (identity: {})",
             tccl.getClass().getName(), System.identityHashCode(tccl));
    log.info("[DIAG] Same as plugin CL? {}", tccl == cl);
}
```

### A-3: Implement Class Visibility & Identity Tests

```java
private void testClassVisibility() {
    Logger log = LoggerFactory.getLogger(ClassloaderDiagnosticRule.class);
    ClassLoader cl = this.getClass().getClassLoader();

    // Test visibility of key classes
    String[] classesToTest = {
        // Jackson (core question)
        "com.fasterxml.jackson.databind.ObjectMapper",
        "com.fasterxml.jackson.databind.JsonNode",
        "com.fasterxml.jackson.databind.node.ObjectNode",
        "com.fasterxml.jackson.core.JsonFactory",
        "com.fasterxml.jackson.annotation.JsonProperty",

        // Other potential PA-provided libraries
        "org.yaml.snakeyaml.Yaml",
        "org.slf4j.LoggerFactory",
        "ch.qos.logback.classic.Logger",
        "org.apache.commons.lang3.StringUtils",
        "com.google.common.collect.ImmutableList",

        // Jakarta
        "jakarta.validation.Validator",
        "jakarta.inject.Inject",

        // PA SDK (should always be visible)
        "com.pingidentity.pa.sdk.policy.RuleInterceptor",
        "com.pingidentity.pa.sdk.http.Exchange",
    };

    for (String className : classesToTest) {
        try {
            Class<?> loaded = cl.loadClass(className);
            ClassLoader definingCL = loaded.getClassLoader();
            String version = loaded.getPackage() != null
                ? loaded.getPackage().getImplementationVersion()
                : "unknown";
            log.info("[DIAG] ✅ {} — version: {}, defining CL: {} ({})",
                     className, version,
                     definingCL != null ? definingCL.getClass().getName() : "bootstrap",
                     System.identityHashCode(definingCL));
        } catch (ClassNotFoundException e) {
            log.info("[DIAG] ❌ {} — NOT VISIBLE from plugin classloader", className);
        }
    }
}

private void testClassIdentity(Exchange exchange) {
    Logger log = LoggerFactory.getLogger(ClassloaderDiagnosticRule.class);

    // If we can load ObjectMapper, create a JsonNode and compare with PA's
    try {
        // Our JsonNode (loaded via plugin classloader)
        Class<?> omClass = this.getClass().getClassLoader()
            .loadClass("com.fasterxml.jackson.databind.ObjectMapper");
        Object om = omClass.getDeclaredConstructor().newInstance();
        java.lang.reflect.Method createObj = omClass.getMethod("createObjectNode");
        Object ourNode = createObj.invoke(om);

        log.info("[DIAG] Our ObjectNode class: {} (CL: {})",
                 ourNode.getClass().getName(),
                 ourNode.getClass().getClassLoader());

        // PA's JsonNode (from Identity, if available)
        Identity identity = exchange.getIdentity();
        if (identity != null) {
            Object paNode = identity.getAttributes();
            if (paNode != null) {
                log.info("[DIAG] PA's JsonNode class: {} (CL: {})",
                         paNode.getClass().getName(),
                         paNode.getClass().getClassLoader());
                log.info("[DIAG] ⭐ Same class? {}",
                         ourNode.getClass().getClassLoader() ==
                         paNode.getClass().getClassLoader());
            } else {
                log.info("[DIAG] PA Identity has null attributes");
            }
        } else {
            log.info("[DIAG] No Identity on exchange (unauthenticated)");
        }
    } catch (Exception e) {
        log.warn("[DIAG] Class identity test failed", e);
    }
}
```

### A-4: Build Diagnostic Shadow JAR

Build a **minimal** shadow JAR that includes only:
- The diagnostic plugin class
- `META-INF/services/` SPI file
- NO Jackson, NO JSLT, NO SnakeYAML (we want to test if PA provides them)

```bash
./gradlew :diagnostic-plugin:shadowJar
# Output: diagnostic-plugin/build/libs/diagnostic-plugin-shadow.jar
```

The JAR should be very small (< 100 KB — just a few classes).

### A-5: Deploy to PA 9.0 Docker

```bash
# Start PA with diagnostic plugin mounted
docker run -d --name pa-diag \
  -p 9000:9000 -p 3000:3000 \
  -v $(pwd)/diagnostic-plugin/build/libs/diagnostic-plugin-shadow.jar:/opt/server/deploy/diagnostic-plugin.jar \
  pingidentity/pingaccess:9.0.1-latest

# Wait for PA to start
docker logs -f pa-diag 2>&1 | grep -i "PingAccess is running"

# Via PA Admin API (port 9000, default creds admin/2Access):
# 1. Create a rule of type "ClassloaderDiagnostic"
# 2. The configure() method fires → diagnostics logged
# 3. Optionally: create a site + application + resource using the rule,
#    send a test request to trigger handleRequest() for class identity tests
```

### A-6: Collect and Analyze Logs

```bash
# Extract diagnostic output
docker logs pa-diag 2>&1 | grep "\[DIAG\]" > classloader-diagnostics.log

# Key answers to extract:
# 1. Classloader type (e.g., URLClassLoader, custom PA classloader)
# 2. Parent chain (how many levels, what types)
# 3. Delegation model (parent-first = standard, child-first = OSGi-like)
# 4. Jackson visibility (✅ or ❌)
# 5. Jackson class identity (same classloader or different)
# 6. Full list of visible/invisible libraries
```

### A-7: Document Findings in SDK Guide

Add a new subsection to SDK guide §9 titled **"Classloader Model (Verified)"**
with:

- Classloader hierarchy diagram
- Visibility matrix (which PA libraries are visible to plugins)
- Delegation model explanation
- Jackson version discovered at runtime
- Implications for dependency management

### A-8: Draft ADR on Dependency Strategy

If Jackson is confirmed visible and shared:

**ADR-0031: PA-Provided Dependencies (No Jackson Relocation)**

- **Status**: Proposed → Accepted (after spike validation)
- **Context**: PA's classloader makes Jackson visible to plugins; SDK API
  exposes Jackson types directly
- **Decision**: Declare Jackson as `compileOnly` (not bundled, not relocated);
  version-pin to PA's shipped version
- **Consequences**: Eliminates boundary conversion, dual ObjectMapper, and
  shaded package names; requires version-locked plugin releases

If Jackson is NOT visible (unlikely but possible):

**ADR-0031: Jackson Relocation Required**

- **Status**: Accepted
- **Context**: Testing confirmed PA uses classloader isolation
- **Decision**: Keep the current relocation approach
- **Consequences**: Boundary conversion required (current design stands)

### A-9: Determine Spec Impact

If the spike **confirms** Jackson visibility (expected outcome):

| Spec Section | Current Text | Change |
|-------------|-------------|--------|
| FR-002-09 (Deployment Packaging) | "shadow JAR **MUST** use Gradle Shadow's `relocate` feature to shade Jackson" | Remove relocation requirement; declare Jackson as `compileOnly` |
| FR-002-09 "Jackson relocation" | Entire subsection on mandatory relocation | Replace with "PA-provided dependency" section |
| FR-002-09 "Dual ObjectMapper pattern" | Two ObjectMapper instances | Remove entirely |
| FR-002-06 (Session Context) | "⚠️ Critical — Jackson boundary conversion" | Remove boundary conversion; `Identity.getAttributes()` returns same `JsonNode` class |
| FR-002-06 | `buildSessionContext()` boundary conversion code example | Simplify to direct `session.set(key, value)` |
| Constraint 8 (FR-002-09 area) | "All PA-sourced `JsonNode` values MUST be boundary-converted" | Remove entirely |
| SDK guide §6 | "Boundary Conversion (Jackson Relocation)" subsection | Remove or rewrite as "no conversion needed" |
| SDK guide §9 | "Jackson Relocation (MANDATORY)" | Replace with "PA-Provided Dependencies" |
| NFR-002-02 | "Shadow JAR size MUST be < 20 MB" | Tighten to < 5 MB (no bundled Jackson) |
| S-002-24 | "Shadow JAR correctness: all deps bundled" | Update to reflect `compileOnly` strategy |
| N-002-05 | "avoids classloader conflicts from bundling metrics frameworks in the shadow JAR" | Update rationale if classloader behavior is now documented |

---

## Risks & Mitigations

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| PA uses strict classloader isolation (child-first) | Low — SDK API exposes Jackson types | Spike will definitively answer this; fallback is current relocation design |
| PA changes classloader model in future versions | Low — breaking change for all plugins | Version-locked releases; runtime version guard in `configure()` |
| Diagnostic plugin fails to deploy | Low | Minimal plugin, follows exact SDK sample pattern |
| PA Docker image requires license for rule creation | Medium | Can be worked around with trial license or by inspecting logs from `configure()` only |
| Jackson class is visible but a different version than expected | Low | Spike B (dependency extraction) addresses this directly |

---

## Success Criteria

- [ ] Classloader type, parent chain, and delegation model are documented
- [ ] Visibility of Jackson, SnakeYAML, SLF4J, Logback, Jakarta Validation,
      Commons, Guava is mapped (✅/❌ for each)
- [ ] Class identity of `JsonNode` (plugin vs. PA) is verified
- [ ] Jackson runtime version is captured
- [ ] SDK guide §9 updated with verified classloader model
- [ ] ADR drafted (accept or reject relocation)
- [ ] Spec impact list is finalized (which sections to amend)

---

## Dependencies

- **Spike B** (PA Dependency Extraction) — complements this spike. Spike A
  answers "can we see PA's libraries?"; Spike B answers "what versions are they?"
  They can be executed independently but together provide the full picture.
- **PA Docker image** — `pingidentity/pingaccess:9.0.1-latest` must be
  pullable. If not, the local `binaries/pingaccess-9.0.1.zip` can be used
  to build a local image.
- **SDK JAR** — available at `docs/reference/pingaccess-sdk/pingaccess-sdk-9.0.1.0.jar`.

---

*Created: 2026-02-11 | Owner: Ivan | Feature: 002 — PingAccess Adapter*

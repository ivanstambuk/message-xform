# Spike A: PingAccess Classloader Model Discovery

Status: **✅ Resolved (Static Analysis)** | Created: 2026-02-11 | Feature: 002

> **Verdict:** PingAccess uses a **flat classpath** with **no classloader
> isolation**. `lib/*` and `deploy/*` share the same
> `jdk.internal.loader.ClassLoaders$AppClassLoader`. Jackson relocation is
> **unnecessary and counterproductive**.

## Tracker

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| A-0 | Reverse-engineer PA startup and classloading from bytecode | ✅ | `run.sh` + `javap` decompilation — definitive answer |
| A-1 | Create diagnostic plugin skeleton | ⏭️ Skipped | Not needed — static analysis answered the question |
| A-2 | Implement classloader inspection in `configure()` | ⏭️ Skipped | See A-0 |
| A-3 | Implement runtime class identity tests | ⏭️ Skipped | See A-0 |
| A-4 | Build diagnostic shadow JAR (minimal) | ⏭️ Skipped | See A-0 |
| A-5 | Deploy to PA 9.0 Docker container | 🔲 Optional | Runtime confirmation (recommended but not blocking) |
| A-6 | Collect and analyze logs | 🔲 Optional | Only if A-5 is executed |
| A-7 | Document findings in SDK guide §9 | 🔲 | New "Classloader Model (Verified)" subsection |
| A-8 | Draft ADR on dependency strategy | 🔲 | ADR-0031 or next available |
| A-9 | Determine spec impact | ✅ | Impact list finalized (see below) |

---

## Findings: Reverse-Engineering PA's Classloader Model

### Method

Instead of building and deploying a diagnostic plugin, we reverse-engineered
PingAccess 9.0.1's startup code through:

1. **Extracting `run.sh`** from the Docker image (`/opt/out/instance/bin/run.sh`)
2. **Decompiling bytecode** with `javap -c -p` on PA's internal JARs:
   - `pingaccess-cli-9.0.1.0.jar` — `Starter`, `Bootstrap` classes
   - `pingaccess-engine-9.0.1.0.jar` — `PluginRegistry`, `BundleSupport`
   - `pingaccess-sdk-9.0.1.0.jar` — `ServiceFactory`
3. **Extracting `/opt/server/lib/`** contents (146 JARs) from Docker image
4. **Analyzing Spring plugin config** — `ConfigurablePluginPostProcessor`,
   `RulePluginPostProcessor`, `SimpleServiceLoaderBDRegistryPostProcessor`

### Finding 1: Flat Classpath (No Classloader Isolation)

**Source:** `run.sh` line 59

```bash
CLASSPATH="${CLASSPATH}:${SERVER_ROOT_DIR}/lib/*:${SERVER_ROOT_DIR}/deploy/*"
```

And the JVM launch (line 64, 93-94):

```bash
exec "${JAVA_HOME}"/bin/java ${JAVA_OPTS} ${JVM_OPTS} \
    ...
    -classpath "${CLASSPATH}" \
    com.pingidentity.pa.cli.Starter "$@"
```

**This is a standard `-classpath` launch.** There is no custom classloader
creation, no `URLClassLoader` wrapping, no OSGi, no module system. PA places
**all** JARs — platform libs (`lib/*`) AND plugin JARs (`deploy/*`) — on
the same flat classpath.

**Implication:** The JVM's application classloader
(`jdk.internal.loader.ClassLoaders$AppClassLoader`) loads **everything**.
Plugin code and PA code share the **exact same classloader**. There is
**zero classloader isolation**.

### Finding 2: ServiceLoader Uses Default Classloader

**Source:** `ServiceFactory.java` bytecode (SDK JAR)

```
// ServiceFactory.getImplClasses():
invokestatic  #24  // Method java/util/ServiceLoader.load:(Ljava/lang/Class;)Ljava/util/ServiceLoader;
```

This is `ServiceLoader.load(Class<S>)` — the **single-argument** form. From
the JavaDoc:

> *"Uses the thread's context class loader as the class loader."*

In a standard `-classpath` launch, the thread context classloader is the
application classloader — which has visibility over **all** JARs on the
classpath (`lib/*` + `deploy/*`).

**Confirmation from `ConfigurablePluginPostProcessor`** (engine bytecode):

```
// ConfigurablePluginPostProcessor.getClasses():
invokestatic  #56  // Method java/util/ServiceLoader.load:(Ljava/lang/Class;)Ljava/util/ServiceLoader;
```

Same pattern. PA discovers plugins via `ServiceLoader.load()` using the
default classloader. No custom classloader is involved.

### Finding 3: No Custom Classloader in Bootstrap

**Source:** `Bootstrap.java` bytecode (CLI JAR)

```java
// Bootstrap.invokeMain():
this.getClass().getClassLoader().loadClass(className);
```

The `Starter` class delegates to `Bootstrap`, which loads the router CLI
class using its own classloader — `this.getClass().getClassLoader()`. Since
`Bootstrap` is loaded from the application classpath, this is the application
classloader. **No child classloader is created.**

**Source:** Scanning all PA JARs for classloader-related classes:

```bash
jar tf pingaccess-cli-9.0.1.0.jar | grep -iE "classload|URLClass"
# Only result: LocalizationResourceClassLoaderUtils.class (i18n resource loading only)
jar tf pingaccess-engine-9.0.1.0.jar | grep -iE "classload|URLClass"
# No results
jar tf pingaccess-admin-9.0.1.0.jar | grep -iE "classload|URLClass"
# No results
```

There is **no custom classloader class** anywhere in PA's codebase. The only
`URLClassLoader` usage is `LocalizationResourceClassLoaderUtils` which is
solely for loading localization resource bundles (`.properties` files), not
for plugin isolation.

### Finding 4: Plugin Classes Registered as Spring Prototype Beans

**Source:** `ConfigurablePluginPostProcessor.consumePluginDescriptor()` bytecode

```java
// createBeanDefinition():
BeanDefinitionBuilder.genericBeanDefinition(implClass)
    .setScope("prototype")
    .getBeanDefinition();
```

Discovered plugin implementation classes are registered as Spring prototype
beans directly in the application context. **No classloader boundary.** PA's
Spring context (`spring-context-6.2.11.jar`) manages plugin lifecycle using
the same classloader that loaded the plugin classes.

### Finding 5: Jackson 2.17.0 Ships in PA 9.0.1

**Source:** Extracted `/opt/server/lib/` directory contents

```
jackson-annotations-2.17.0.jar        (78 KB)
jackson-core-2.17.0.jar               (581 KB)
jackson-databind-2.17.0.jar           (1.6 MB)
jackson-datatype-jdk8-2.17.0.jar      (36 KB)
jackson-datatype-jsr310-2.17.0.jar    (132 KB)
third-party-jackson-core-2.17.131.jar (390 KB)  ← AWS SDK's shaded Jackson
```

Jackson **2.17.0** is PA's version. The `third-party-jackson-core` is
AWS SDK's own shaded copy (package `software.amazon.awssdk.thirdparty.jackson`)
and is irrelevant to plugins.

### Finding 6: Full PA-Provided Library Inventory

146 JARs live in `/opt/server/lib/`. Key plugin-relevant libraries:

| Library | Version | Available to Plugins? |
|---------|---------|----------------------|
| **jackson-databind** | 2.17.0 | ✅ Yes (flat classpath) |
| **jackson-core** | 2.17.0 | ✅ Yes |
| **jackson-annotations** | 2.17.0 | ✅ Yes |
| **jackson-datatype-jdk8** | 2.17.0 | ✅ Yes |
| **jackson-datatype-jsr310** | 2.17.0 | ✅ Yes |
| **slf4j-api** | 1.7.36 | ✅ Yes |
| **log4j-api** | 2.24.3 | ✅ Yes (NOT Logback — Log4j2) |
| **log4j-slf4j-impl** | 2.24.3 | ✅ Yes (SLF4J→Log4j2 bridge) |
| **jakarta.validation-api** | 3.1.1 | ✅ Yes |
| **jakarta.inject-api** | 2.0.1 | ✅ Yes |
| **hibernate-validator** | 7.0.5.Final | ✅ Yes |
| **commons-lang3** | 3.14.0 | ✅ Yes |
| **guava** | 33.1.0-jre | ✅ Yes |
| **spring-context** | 6.2.11 | ✅ Yes |
| **spring-beans** | 6.2.11 | ✅ Yes |
| **netty** (multiple) | 4.1.127.Final | ✅ Yes |
| SnakeYAML | — | ❌ **Not shipped** |

> **Important correction:** PA uses **Log4j2** (not Logback) as its logging
> backend, with `log4j-slf4j-impl` bridging SLF4J 1.x → Log4j2. The SDK
> guide §9 incorrectly stated Logback. Also note: PA uses **SLF4J 1.7.36**
> (SLF4J 1.x API, not 2.x).

### Classloader Model Diagram

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

---

## Conclusions

### Answer to Each Original Question

| # | Question | Answer |
|---|----------|--------|
| 1 | What classloader type does PA use for plugin JARs? | `jdk.internal.loader.ClassLoaders$AppClassLoader` (standard JVM application classloader) |
| 2 | What is the parent classloader chain? | AppClassLoader → PlatformClassLoader → BootstrapClassLoader (standard JDK hierarchy) |
| 3 | Can plugin code see PA's internal libraries? | **Yes, all of them.** Flat classpath means `lib/*` and `deploy/*` share the same classloader. |
| 4 | Is `JsonNode` the same `Class` object for plugins and PA? | **Yes.** Single classloader → single `Class` instance per fully-qualified name. |
| 5 | What is the delegation model? | **Standard parent-first** (default JVM behavior). No child-first, no OSGi, no custom delegation. |

### Impact: Jackson Relocation is Unnecessary

Since there is **no classloader isolation**, Jackson relocation would actually
be **harmful**:

1. If we bundle and relocate Jackson, our code uses
   `io.messagexform.shaded.jackson.databind.JsonNode` — but `Identity.getAttributes()`
   returns `com.fasterxml.jackson.databind.JsonNode`. These are **different
   classes** and we'd get `ClassCastException` at the boundary.

2. The "boundary conversion" pattern (serialize to `byte[]`, deserialize back)
   was designed to work around this — but it's solving a problem **that
   doesn't exist** on PA's flat classpath.

3. If we bundle Jackson **without** relocating, we create a version conflict:
   two copies of `com.fasterxml.jackson.databind.ObjectMapper` on the
   classpath (PA's and ours). The JVM loads whichever it finds first — which
   is unpredictable and fragile.

**The correct approach is `compileOnly` — don't bundle Jackson at all.**
PA provides it, plugins share it, and the SDK API is designed around this.

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

### The Hypothesis ✅ CONFIRMED

The PingAccess SDK API **directly exposes Jackson types** in its public
interfaces:

- `Identity.getAttributes()` → returns `JsonNode`
- `SessionStateSupport.setAttribute(String, JsonNode)` → takes `JsonNode`
- `PluginConfiguration` uses `@JsonProperty`, `@JsonUnwrapped`
- PA's `configure()` step does Jackson deserialization of the config JSON
  into the plugin's config class

This strongly implies — and static analysis now **confirms** — that **PA
expects plugins to share its Jackson instance**. There is no classloader
isolation; plugins and PA share the same flat classpath.

### Original Goal (Superseded)

Deploy a minimal diagnostic plugin to PA 9.0 Docker and **definitively
answer** class loading questions.

**Status:** All questions answered via static analysis (reverse-engineering
`run.sh` and `javap` decompilation). A runtime diagnostic plugin deployment
is optional for further confirmation but is not blocking.

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

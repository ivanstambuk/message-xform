# PingAccess Native Groovy Scripting — Can It Replace the message-xform Plugin?

> Source: *PingAccess 9.0 Official Documentation* (`docs/reference/vendor/pingaccess-9.0.txt`)
> and existing research (`docs/research/pingaccess-plugin-api.md`)  
> Investigation date: 2026-02-18

---

## Executive Summary

**YES — PingAccess Groovy script rules CAN rewrite both request and response payloads (including JSON bodies) without a custom Java plugin.** The API surface is identical: Groovy scripts access the exact same `Exchange`, `Request`, `Response`, `Body`, and `Header` objects as compiled Java `RuleInterceptor` plugins. The critical method `setBodyContent(byte[])` is fully documented and available for both directions.

However, there are significant **trade-offs** in maintainability, performance, testability, and reuse that make the custom plugin approach superior for a product like message-xform. Details below.

---

## 1. Groovy Scripting API for Payload Rewrite

### 1.1 Available Objects

| Object | Groovy Access | Purpose |
|--------|---------------|---------|
| Exchange | `exc` | Root object — contains request + response |
| Request | `exc?.request` | HTTP request (headers, body, URI, method) |
| Response | `exc?.response` | HTTP response (headers, body, status) — **null until site responds** |
| Body | `exc?.request?.body` / `exc?.response?.body` | Raw body content |
| Header | `exc?.request?.header` / `exc?.response?.header` | All HTTP headers |
| Logger | `exc?.log` | Logging (requires `log4j2.xml` config) |

### 1.2 Critical Methods for Payload Rewrite

**Reading body content** (both request and response):
```groovy
byte[] getContent()   // Body.getContent() — returns raw bytes
int getLength()       // Body.getLength() — returns content length
```

**Replacing body content** (both request and response):
```groovy
// Request — documented at line 15972 of PA 9.0 docs:
void setBodyContent(byte[] content)
// "Replaces the body content of the request. This method will also
//  adjust the Content-Length header field to align with the length
//  of the specified content."

// Response — documented at line 16048 of PA 9.0 docs:
void setBodyContent(byte[] content)
// "Replaces the body content of the response. This method will also
//  adjust the Content-Length header field to align with the length
//  of the specified content."
```

**URI rewriting** (request only):
```groovy
String uri              // exc?.request?.uri — read the URI
void setUri(String)     // exc?.request?.setUri(newUri) — rewrite the URI
```

### 1.3 Proof: Full JSON Payload Transform in Groovy

```groovy
// ── REQUEST DIRECTION: Clean JSON → PingAM Callback Format ──
if (!exc?.response) {
    // We're in request phase (response is null)
    def body = exc?.request?.body
    if (body) {
        def json = new groovy.json.JsonSlurper().parse(body.getContent())
        
        // Transform: clean API → PingAM callback format
        def transformed = [
            authId: json.authId ?: "",
            callbacks: [
                [
                    type: "NameCallback",
                    output: [[name: "prompt", value: "User Name:"]],
                    input: [[name: "IDToken1", value: json.username ?: ""]]
                ],
                [
                    type: "PasswordCallback", 
                    output: [[name: "prompt", value: "Password:"]],
                    input: [[name: "IDToken2", value: json.password ?: ""]]
                ]
            ]
        ]
        
        def result = new groovy.json.JsonOutput().toJson(transformed)
        exc.request.setBodyContent(result.getBytes("UTF-8"))
        exc?.request?.header?.setContentType("application/json")
    }
}
pass()
```

```groovy
// ── RESPONSE DIRECTION: PingAM Callback → Clean JSON ──
if (exc?.response) {
    def body = exc?.response?.body
    if (body?.getLength() > 0) {
        def contentType = exc?.response?.header?.getContentType()
        if (contentType?.matchesBaseType("application/json")) {
            def json = new groovy.json.JsonSlurper().parse(body.getContent())
            
            // Transform: PingAM callbacks → clean API response
            def clean = [:]
            if (json.tokenId) {
                clean.token = json.tokenId
                clean.status = "success"
            } else if (json.callbacks) {
                clean.stage = json.authId ? "callback" : "initial"
                clean.authId = json.authId
                clean.prompts = json.callbacks.collect { cb ->
                    [type: cb.type, fields: cb.input?.collect { [name: it.name] }]
                }
            }
            
            def result = new groovy.json.JsonOutput().toJson(clean)
            exc.response.setBodyContent(result.getBytes("UTF-8"))
        }
    }
}
pass()
```

### 1.4 Processing Phases

From the PA 9.0 docs (line 14965):

> "Groovy script rules are invoked during the **request processing phase** of an exchange,
> allowing the script to modify the request before it is sent to the server. Groovy script
> rules are also invoked during the **response**, allowing the script to modify the response
> before it is returned to the client."

The processing model matches exactly what `RuleInterceptor.handleRequest()` and
`RuleInterceptor.handleResponse()` do in the Java plugin.

**Phase detection in Groovy** — check if `exc?.response` is null:
- `null` → request phase (pre-backend)
- non-null → response phase (post-backend)

---

## 2. Deployment & Operations Differences

| Aspect | Java Plugin (message-xform) | Groovy Script Rule |
|--------|---------------------------|-------------------|
| **Where code lives** | JAR in `<PA_HOME>/deploy/` | Inline in PA Admin UI (text field) |
| **Reload on change** | ❌ Full PA restart required | ✅ Save in admin UI → auto-propagates to engines |
| **Syntax validation** | Compile-time (Maven) | Save-time in admin UI (syntactic check only) |
| **Version control** | Git (JAR source) | ❌ Stored in PA datastore; must export via Admin API for Git |
| **Testability** | JUnit, integration tests | ❌ No unit test framework; test only via live PA |
| **Configuration** | Typed `PluginConfiguration` with admin UI fields | ❌ Hardcoded in script |
| **Reusability** | Core library shared across adapters | ❌ Script per PA instance; copy-paste for reuse |
| **IDE support** | Full Java IDE (IntelliJ, etc.) | ❌ Text editor in browser |
| **Debugging** | Standard Java debugging | `exc?.log` + `<PA_HOME>/log/pingaccess.log` |
| **Error handling** | Try/catch with typed exceptions | Script failure → rule fail → generic PA error page |
| **Max complexity** | Unlimited (full Java ecosystem) | Practical limit: hundreds of lines before unmaintainable |

### 2.1 Hot Reload — Groovy's Key Advantage

This is the single biggest operational advantage of Groovy scripts. From the PA 9.0 docs (line 15080):

> "Groovy script rules are evaluated when saved to ensure that they are syntactically valid."

Changes saved in the admin UI are automatically replicated to PA engine nodes via the
admin-to-engine polling mechanism. **No restart required.** This is valuable for:
- Rapid prototyping
- Emergency production fixes
- Small, site-specific customizations

### 2.2 Agent vs Site Deployment — Same Limitation as Java Plugin

From PA 9.0 docs (line 15947):

> "**Body getBody** — Contains the HTTP body information from the request sent to the
> application. **This field is not available in scripts used with an agent.**"

And (line 15983):

> "The fields and methods for the Response object are not available in scripts used with an agent."

**Bottom line**: Groovy script payload rewriting only works in **proxy (Site) deployments** — 
same constraint as the Java plugin. Agent deployments have no access to request body or
response objects.

---

## 3. Limitations & Risks of the Groovy Approach

### 3.1 No Declarative Transform Model

message-xform's core value proposition is the **declarative YAML + JSLT transform spec**.
Operators define transforms without writing code:

```yaml
# message-xform declarative spec
direction: response
engine: jslt
expression: |
  {
    "token": .tokenId,
    "status": if(.tokenId) "success" else "pending"
  }
```

Groovy scripts are **imperative code** — every transform is handwritten procedural logic.
For a product with dozens of transform profiles across multiple APIs, this becomes
an unmaintainable mess.

### 3.2 No Unit Testing

Java plugins can be tested with JUnit (message-xform has **21,409 test lines**, 78.2% ratio).
Groovy scripts can only be tested by deploying to a live PingAccess instance, which means:
- No CI/CD test pipeline
- No regression detection
- No test coverage metrics

### 3.3 No Composition or Chaining

message-xform supports **pipeline chaining** (ADR-0012) — multiple transforms executed
in sequence via declarative YAML:

```yaml
chains:
  - response-strip-internal
  - response-webauthn-transform
  - response-header-inject
```

In Groovy, all logic must live in a single script (or you use multiple rules, but they
share no state cleanly — only `exc.setProperty(key, value)` is available for cross-rule
communication).

### 3.4 Performance Considerations

- **Groovy 2.4** is bundled with PA 9.0 (from `pa-provided-deps.md`: Groovy 2.4.21)
- Groovy is a dynamic language → runtime reflection → **3-5x slower** than compiled Java
- `@CompileStatic` could reduce to 1-2x but is not available in the PA admin UI script editor
- JSON parsing via `JsonSlurper` is adequate for small payloads but less efficient than
  Jackson's streaming parser used by message-xform
- No connection pooling or async support (unlike `AsyncRuleInterceptor` with `HttpClient`)

### 3.5 Security Surface

From PA 9.0 docs (line 15002):

> "Through Groovy scripts, PingAccess administrators can perform **sensitive operations**
> that could affect **system behavior and security**."

Groovy scripts run with full JVM privileges — they can:
- Execute system commands (`Runtime.exec()`)
- Access the filesystem
- Open network connections
- Load arbitrary classes

This is a significant security consideration in multi-tenant or regulated environments.

### 3.6 Version Control & Auditability

Groovy scripts live in the PA admin datastore, not in Git. While they can be exported
via the PA Admin API, this requires custom tooling to:
- Extract scripts into files
- Track changes over time
- Review diffs
- Enforce approval workflows

message-xform's Git-native approach (YAML specs + compiled Java) provides a natural
audit trail with standard Git workflows.

### 3.7 Cannot Change HTTP Method

From the PA documentation and community feedback: Groovy scripts can **read** the HTTP
method (`exc?.request?.method?.methodName`) but **cannot change it**. If a transform
requires converting a GET to a POST (or vice versa), a custom Java plugin is required.

---

## 4. Built-in Alternative: Rewrite Content Rules

PA also provides **Rewrite Content Rules** — a built-in, no-code rule type for 
text-based response body modifications:

| Aspect | Rewrite Content Rule |
|--------|---------------------|
| **What it does** | Regex find-and-replace in response body |
| **Direction** | ❌ Response only |
| **Content types** | `text/html`, `text/plain`, `application/json` (configurable) |
| **Complexity** | Simple string substitution only |
| **Compression** | Auto-decompresses gzip/deflate before processing |
| **Agent support** | ❌ Not available for agents |
| **Performance** | ⚠️ "Extensive use might have significant performance implications" |

**Verdict**: Rewrite Content Rules are useful for simple hostname rewrites in HTML/JS
responses but **completely insufficient** for structural JSON transformation. They only
support text search-and-replace, not JSON parsing, restructuring, field mapping, or
conditional logic.

---

## 5. Comparative Assessment

### What Groovy CAN do (matching message-xform capabilities):

| Capability | Supported? | Notes |
|-----------|:----------:|-------|
| Read request body | ✅ | `exc?.request?.body?.getContent()` |
| Write request body | ✅ | `exc?.request?.setBodyContent(bytes)` |
| Read response body | ✅ | `exc?.response?.body?.getContent()` |
| Write response body | ✅ | `exc?.response?.setBodyContent(bytes)` |
| Parse JSON | ✅ | `groovy.json.JsonSlurper` |
| Generate JSON | ✅ | `groovy.json.JsonOutput` |
| Read/write headers | ✅ | Full Header API |
| Rewrite request URI | ✅ | `exc?.request?.setUri(newUri)` |
| Set status code | ✅ | `exc?.response?.setStatusCode(int)` |
| Read query params | ✅ | `exc?.request?.getQueryStringParams()` |
| Access identity/tokens | ✅ | `exc?.identity`, OAuth token via `policyCtx` |
| Conditional logic | ✅ | Full Groovy language |
| Logging | ✅ | `exc?.log.info(...)` (requires log4j2.xml config) |

### What Groovy CANNOT do (vs message-xform):

| Capability | Gap | Impact |
|-----------|-----|--------|
| Declarative transform specs | No YAML/JSLT — imperative code only | High — core product value lost |
| Unit testing | No test framework | High — no regression detection |
| Pipeline chaining | Single script per rule | Medium — complex transforms are monolithic |
| Change HTTP method | Read-only method access | Low — rare requirement |
| Async HTTP calls | No HttpClient injection | Low — only needed for external calls |
| Expression engine SPI | No pluggable engines | Medium — locked to Groovy |
| Typed configuration UI | No PluginConfiguration | Medium — no admin-friendly config |
| Performance | Dynamic Groovy ~3-5x slower | Medium — depends on throughput needs |
| Cross-adapter reuse | Copy-paste only | High — no portable transform core |

---

## 6. Verdict & Recommendations

### When Groovy Scripts ARE the right choice:

1. **Quick proof-of-concept** — Testing whether a payload transform approach works
   before investing in a Java plugin
2. **One-off, site-specific transforms** — A single customer needs a small tweak that
   doesn't warrant a plugin update
3. **Emergency fixes** — Hot-reloadable without PA restart
4. **Simple enrichment** — Adding a header, injecting identity info, or minor JSON tweaks
5. **Environments without build pipeline** — When deploying custom JARs isn't feasible

### When the Java plugin IS the right choice (message-xform's position):

1. **Product with multiple transform profiles** — Declarative YAML specs are essential
2. **CI/CD test pipeline required** — The 78.2% test coverage ratio is non-negotiable
3. **Cross-gateway portability** — Same core runs on PingAccess, standalone, potentially
   PingGateway
4. **Complex JSON restructuring** — Deep nested transforms, conditional branches,
   multi-step pipelines
5. **Performance-sensitive** — Compiled Java with Jackson streaming is significantly faster
6. **Auditability** — Git-native versioning with full change history
7. **Team development** — IDE support, code review, typed interfaces

### Hybrid Approach (Best of Both):

For maximum flexibility, consider offering **both** deployment options:

```
Option A: message-xform Java Plugin (current)
  └── Full declarative YAML + JSLT pipeline
  └── Compile → deploy → restart

Option B: Groovy Script Bridge (potential future feature)
  └── Minimal Groovy script that calls message-xform-core
  └── Core library bundled as JAR in <PA_HOME>/lib/
  └── Hot-reloadable spec path parameter
  └── Still requires core JAR deployment, but spec changes are live
```

This hybrid would give you the **hot-reload benefit** of Groovy for spec changes while
keeping the **tested, declarative transform engine** of message-xform-core.

---

## 7. References

| Source | Location |
|--------|----------|
| PA 9.0 — Groovy in PingAccess overview | `pingaccess-9.0.txt` line 14938 |
| PA 9.0 — Rule processing phases | `pingaccess-9.0.txt` line 14964 |
| PA 9.0 — Body object reference | `pingaccess-9.0.txt` line 15089 |
| PA 9.0 — Request object / `setBodyContent` | `pingaccess-9.0.txt` line 15868, 15972 |
| PA 9.0 — Response object / `setBodyContent` | `pingaccess-9.0.txt` line 15978, 16048 |
| PA 9.0 — Adding Groovy script rules (admin) | `pingaccess-9.0.txt` line 20301 |
| PA 9.0 — Rewrite content rules | `pingaccess-9.0.txt` line 21766 |
| PA 9.0 — `setBodyContent` bug fix (PA-7068) | `pingaccess-9.0.txt` line 2255 |
| PA 9.0 — Groovy version bundled | `pa-provided-deps.md` line 177 (Groovy 2.4.21) |
| Existing research — Plugin API | `docs/research/pingaccess-plugin-api.md` |
| Feature 002 spec — Non-goal N-002-02 | `docs/architecture/features/002/spec.md` line 81 |

---

*Status: COMPLETE — Deep investigation of PingAccess Groovy payload rewrite capabilities*

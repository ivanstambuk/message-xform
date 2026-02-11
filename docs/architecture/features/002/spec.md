# Feature 002 – PingAccess Adapter

| Field | Value |
|-------|-------|
| Status | Spec Ready |
| Last updated | 2026-02-11 |
| Owners | Ivan |
| Linked plan | `docs/architecture/features/002/plan.md` |
| Linked tasks | `docs/architecture/features/002/tasks.md` |
| Roadmap entry | #2 – PingAccess Adapter |
| Depends on | Feature 001 (core engine) |

> Guardrail: This specification is the single normative source of truth for the feature.
> Track questions in `docs/architecture/open-questions.md`, encode resolved answers
> here, and use ADRs under `docs/decisions/` for architectural clarifications.

## Overview

Gateway adapter that integrates message-xform with **PingAccess 9.0** via the
Java Add-on SDK. Implements the `AsyncRuleInterceptor` SPI to intercept
request/response flows through PingAccess Sites and apply JSLT-based message
transformations using the core engine.

The adapter is a **thin bridge layer** — all transformation logic lives in
`message-xform-core`. The adapter's sole responsibility is:

1. Reading native `Exchange` data (body, headers, status, URL, identity).
2. Wrapping it into a core `Message` via `GatewayAdapter` SPI.
3. Delegating transformation to `TransformEngine`.
4. Writing transformed results back to the `Exchange`.

**Affected modules:** New module `adapter-pingaccess`.

## Key Research

- PingAccess Plugin API: `docs/research/pingaccess-plugin-api.md` (COMPLETE)
- PingAccess Docker & SDK: `docs/research/pingaccess-docker-and-sdk.md` (COMPLETE)
- PingAM Authentication API: `docs/research/pingam-authentication-api.md` (COMPLETE)
- SDK JAR: `docs/reference/pingaccess-sdk/pingaccess-sdk-9.0.1.0.jar`
- PingAccess 9.0 reference: `docs/reference/pingaccess-9.0.pdf`

## Goals

- G-002-01 – Implement `GatewayAdapter<Exchange>` for PingAccess, bridging the
  PA SDK's `Exchange`/`Request`/`Response`/`Body`/`Headers` model to the core
  engine's `Message` record.
- G-002-02 – Use `AsyncRuleInterceptorBase<T>` for non-blocking request/response
  processing (SDK-recommended pattern, supports `CompletionStage<Outcome>`).
- G-002-03 – Provide a PingAccess admin UI configuration panel via `@UIElement`
  annotations for transform spec directory, error mode, and profile selection.
- G-002-04 – Package as a single deployable JAR for PingAccess's `deploy/`
  directory with correct `META-INF/services` SPI registration.
- G-002-05 – Support Docker-based E2E testing against a real PingAccess 9.0 instance.

## Non-Goals

- N-002-01 – Agent deployment support. PingAccess Agent rules do **not** support
  request body access or response handling (confirmed in SDK docs). This adapter
  targets **Site (gateway) deployment only**.
- N-002-02 – Groovy script rules. The adapter is a compiled Java plugin —
  Groovy scripting is an alternative approach not covered here.
- N-002-03 – Async external HTTP calls during transformation. The adapter uses
  `AsyncRuleInterceptorBase` for the non-blocking lifecycle, but the transformation
  itself (JSLT evaluation) is CPU-bound and runs synchronously within the async
  completion stage. External HTTP calls (e.g., calling PingAM directly) are out of
  scope.
- N-002-04 – PingAccess clustering or HA configuration. The adapter is a
  stateless plugin — it works identically in standalone and clustered PA
  deployments.
- N-002-05 – Third-party metrics frameworks. The adapter does not bundle
  Micrometer, OpenTelemetry, or Prometheus client libraries. Observability is
  provided through two built-in mechanisms: (a) SLF4J logging into PA's audit
  log pipeline (always on), and (b) opt-in JMX MBeans for aggregate counters
  (see FR-002-14). This avoids classloader conflicts from bundling metrics
  frameworks in the shadow JAR.

---

## SDK API Surface

> Full SDK API documentation, method signatures, and implementation patterns are
> in [`docs/architecture/features/002/pingaccess-sdk-guide.md`](pingaccess-sdk-guide.md).
> This section provides a high-level summary only.

The adapter uses the following SDK types. See the guide for complete method
signatures, usage notes, and code examples.

| SDK Type | Role | Guide Section |
|----------|------|---------------|
| `AsyncRuleInterceptorBase<T>` | Plugin base class — provides `handleRequest`, `handleResponse`, lifecycle | §1 |
| `Exchange` | Request/response envelope — holds identity, properties, routing info | §2 |
| `Message` / `Request` / `Response` | HTTP message types with body, headers, status | §3 |
| `Identity` | Authenticated user context — subject, claims, OAuth metadata, session state | §6 |
| `SessionStateSupport` | Persistent session key-value store (read/write `Map<String, JsonNode>`) | §6 |
| `OAuthTokenMetadata` | OAuth client context — clientId, scopes, tokenType, realm, expiry | §6 |
| `ResponseBuilder` | Factory for constructing `Response` objects (critical for DENY mode) | §8 |
| `@Rule` / `@UIElement` | Plugin registration and admin UI configuration annotations | §7 |
| `ExchangeProperty<T>` | Typed cross-rule state on the exchange | §12 |

**Key constraints from SDK analysis:**
- `exchange.getResponse()` is **null** during `handleRequest()` — see guide §8
- `exchange.getIdentity()` may be **null** for unauthenticated resources
- `Identity.getTokenExpiration()` returns `java.time.Instant` (not `Date`)
- Jackson is PA-provided (`compileOnly`) — do NOT bundle or relocate (ADR-0031, guide §9)

**Java version:** PingAccess 9.0 supports Java 17 and 21. The adapter compiles
with Java 21, matching the core module.

---

## Functional Requirements

### FR-002-01: GatewayAdapter Implementation

**Requirement:** The module MUST provide a class `PingAccessAdapter` implementing
`GatewayAdapter<Exchange>` with three methods:

1. `wrapRequest(Exchange)` → `Message`
2. `wrapResponse(Exchange)` → `Message`
3. `applyChanges(Message, Exchange)`

All wrap methods create **deep copies** of the native data (ADR-0013). For body
data, parsing via `objectMapper.readTree(body.getContent())` inherently produces a
new, independent `JsonNode` tree — satisfying the deep-copy requirement without an
explicit `deepCopy()` call. Header maps are deep-copied by creating new `HashMap`
instances with lowercase-normalized keys.

| Aspect | Detail |
|--------|--------|
| Success path | `wrapRequest` reads `Exchange.getRequest()` body/headers/URL and returns a populated `Message` record |
| Validation path | Unit tests with mock `Exchange` objects verify all 9 `Message` fields are populated |
| Failure path | Null body → `NullNode`, null headers → empty map, malformed JSON → `NullNode` + log warning |
| Status | ⬜ Not yet implemented |
| Source | G-002-01, SPI-001-04/05/06 |

#### JSON Parse Failure Strategy

Unlike the `StandaloneAdapter` (which throws `IllegalArgumentException` on bad
JSON and provides separate `wrapRequestRaw()`/`wrapResponseRaw()` methods), the
PingAccess adapter handles parse failures **internally** in `wrapRequest()` and
`wrapResponse()`:

1. **Pre-read the body into memory:** If `!body.isRead()`, call `body.read()`.
   On `AccessException` (body exceeds PA's configured maximum) or `IOException`:
   return `NullNode` as the body and log a warning with exception details.
2. Attempt to parse `body.getContent()` as JSON via `ObjectMapper.readTree()`.
3. On parse failure (malformed JSON, non-JSON content type): **return `NullNode`
   as the body** and log a warning. Do NOT throw.
4. The returned `Message` has all other fields (headers, path, method, etc.)
   populated normally.

> **Why `body.read()` is mandatory:** PingAccess's `Body` is stateful. For
> streamed bodies (`body.isInMemory() == false`), `body.getContent()` throws
> `IllegalStateException` unless `body.read()` has been called first. The
> `body.read()` method loads the body into memory, after which `getContent()`
> returns the byte array. See SDK guide §4 for the Body state model.

**Rationale:** In PingAccess, the adapter does not control the top-level
orchestration flow (unlike `ProxyHandler` in standalone mode). The
`MessageTransformRule` receives the `Message` from `wrapRequest()` and passes
it directly to the engine. Throwing would require the rule to catch and
reconstruct — swallowing inside `wrapRequest()` is cleaner.

**Impact on profile matching:** A `NullNode` body still allows profile matching
on path/method/headers. If a profile matches and attempts a body transformation,
the JSLT expression receives `null` as input — this is handled gracefully by
JSLT (returns `null` for property access on null).

#### wrapRequest Mapping

| Message Field | Source |
|---------------|--------|
| `body` | `exchange.getRequest().getBody()` → pre-read via `body.read()` if `!body.isRead()` → `body.getContent()` → parse as `JsonNode` (or `NullNode` on read failure / empty / non-JSON) |
| `headers` | `exchange.getRequest().getHeaders().asMap()` → flatten `Map<String, String[]>` to single-value `Map<String, String>` (first value per name, lowercase keys) |
| `headersAll` | `exchange.getRequest().getHeaders().asMap()` → convert `Map<String, String[]>` to `Map<String, List<String>>` (all values per name, lowercase keys) |
| `statusCode` | `null` (requests have no status code, per ADR-0020) |
| `contentType` | `exchange.getRequest().getHeaders().getContentType()` → null-safe: `MediaType ct = ...; contentType = (ct != null) ? ct.toString() : null` |
| `requestPath` | `exchange.getRequest().getUri()` (path portion only, strip query string) — see URI choice note below |
| `requestMethod` | `exchange.getRequest().getMethod().getName()` → uppercase string |
| `queryString` | `exchange.getRequest().getUri()` (query portion after `?`, or null) |
| `sessionContext` | See FR-002-06 (identity/session binding) |

> **URI choice:** The adapter uses `Request.getUri()` (the current URI, which
> may already be rewritten by upstream PingAccess rules) rather than
> `Exchange.getOriginalRequestUri()` (the URI before any rule rewrites).
> **Rationale:** Profile matching should operate on the URI that will actually
> reach the backend, not the original client URI. If a prior rule rewrites
> `/old/path` to `/new/path`, the transform spec should match `/new/path`.
> `getOriginalRequestUri()` is available for logging/diagnostics if needed.

#### wrapResponse Mapping

| Message Field | Source |
|---------------|--------|
| `body` | `exchange.getResponse().getBody()` → pre-read via `body.read()` if `!body.isRead()` → `body.getContent()` → parse as `JsonNode` (or `NullNode` on read failure / empty / non-JSON) |
| `headers` | `exchange.getResponse().getHeaders().asMap()` → flatten to single-value map (same normalization as request) |
| `headersAll` | `exchange.getResponse().getHeaders().asMap()` → convert to multi-value map (same normalization as request) |
| `statusCode` | `exchange.getResponse().getStatusCode()` |
| `contentType` | `exchange.getResponse().getHeaders().getContentType()` → null-safe: `(ct != null) ? ct.toString() : null` |
| `requestPath` | `exchange.getRequest().getUri()` (original request path for profile matching) |
| `requestMethod` | `exchange.getRequest().getMethod().getName()` |
| `queryString` | From original request URI |
| `sessionContext` | See FR-002-06 |

> **Logging:** `wrapResponse()` SHOULD log the request path at DEBUG level:
> `LOG.debug("wrapResponse: {} {} → status={}", requestMethod, requestPath, statusCode)`.
> This aids correlation between request and response transforms in PingAccess
> server logs, especially when multiple applications/rules process the same exchange.

> **Header normalization pattern:** `Headers.asMap()` returns `Map<String, String[]>`.
> The adapter produces two maps from this:
> - **Single-value** (`headers`): iterate entries, `key.toLowerCase()` → `values[0]`.
> - **Multi-value** (`headersAll`): iterate entries, `key.toLowerCase()` → `Arrays.asList(values)`.
>
> **Null Content-Type:** `Headers.getContentType()` returns `null` (not an empty
> `MediaType`) when the Content-Type header is absent. The adapter MUST null-check
> before calling `toString()`. This matches the `StandaloneAdapter` behavior where
> Javalin's `ctx.contentType()` also returns `null` for absent Content-Type.

#### applyChanges Direction Strategy

`applyChanges(Message, Exchange)` must apply to the correct side of the
Exchange depending on which phase called it. The `GatewayAdapter<R>` SPI uses
a single method signature with `R = Exchange`; the **caller** (`MessageTransformRule`)
knows the direction and routes accordingly:

- **During `handleRequest`:** caller passes `exchange` and the adapter writes
  to `exchange.getRequest()` (body, headers, URI, method).
- **During `handleResponse`:** caller passes `exchange` and the adapter writes
  to `exchange.getResponse()` (body, headers, status code).

**Chosen approach — two internal helpers:**

The adapter provides two direction-specific internal methods (not part of the
`GatewayAdapter` SPI):

```java
// Internal helpers — called directly by MessageTransformRule:
void applyRequestChanges(Message transformed, Exchange exchange)
void applyResponseChanges(Message transformed, Exchange exchange)
```

`MessageTransformRule` knows the current direction from the lifecycle phase
(`handleRequest` vs `handleResponse`) and calls the appropriate helper directly.
The public SPI method `applyChanges(Message, Exchange)` throws
`UnsupportedOperationException("Use applyRequestChanges() or
applyResponseChanges() directly")`. This is a deliberate safety measure —
the generic SPI method cannot infer direction, and silently defaulting to
response-only would mask directional bugs during request-phase transforms.

> **SPI contract note:** The `GatewayAdapter<R>` SPI does not mandate direction
> awareness — this is adapter-specific. The `StandaloneAdapter` does not need
> direction awareness because Javalin's `Context` API naturally separates
> request and response writes (all writes go through `ctx.result()`,
> `ctx.header()`, `ctx.status()` regardless of phase).

| Phase | Target Side | Fields Written |
|-------|-------------|----------------|
| REQUEST | `exchange.getRequest()` | body via `setBodyContent(bytes)`, headers via `setValues`/`removeFields`, URI via `setUri()`, method via `setMethod()` |
| RESPONSE | `exchange.getResponse()` | body via `setBodyContent(bytes)`, headers via `setValues`/`removeFields`, status via `setStatus(HttpStatus.forCode())` |

> **Note:** `requestPath` and `requestMethod` changes are only applied during
> REQUEST phase. `statusCode` changes are only applied during RESPONSE phase.
> Attempting to write request fields during RESPONSE is silently ignored by
> PingAccess (Constraint #3).

**Body serialization:** The adapter serializes the transformed `JsonNode` to
bytes via `objectMapper.writeValueAsBytes(transformedMessage.body())`. Jackson
defaults to UTF-8 encoding. After calling `setBodyContent(bytes)` (which auto-
updates `Content-Length`), the adapter MUST set the Content-Type header to
`application/json; charset=utf-8` if the body was successfully transformed.
If the original Content-Type had a charset parameter, it is replaced by the
Jackson output charset (always UTF-8).

For `NullNode` bodies (passthrough or non-JSON original), the adapter does NOT
call `setBodyContent()` and does NOT modify Content-Type — the original response
body and Content-Type are preserved unchanged.

#### Header Application Strategy

The core engine's `Message.headers()` contains the **final** header map after
transformation. The adapter cannot simply call `setValues()` for each header —
it must also **remove** pre-existing headers that were dropped by the transform.

The adapter uses a **diff-based** approach:

1. **Capture original headers** at wrap time (snapshot the header names from
   the native `Request`/`Response` before passing to the engine). The original
   header name snapshot is **normalized to lowercase** before diff comparison,
   matching the `Message.headers()` key normalization. This ensures
   case-insensitive diff correctness regardless of PA's original header casing.
2. **After transformation**, compare the original header names with the
   transformed `Message.headers()` keyset.
3. **Apply changes:**
   - Headers in transformed but not in original → `headers.add(name, value)` (new).
   - Headers in both → `headers.setValues(name, List.of(value))` (update).
   - Headers in original but not in transformed → `headers.removeFields(name)` (remove).

This ensures the PingAccess `Headers` object accurately reflects the transform
result without leaking pre-existing headers that the spec intended to remove.

> **Alternative considered:** Clear all headers then set new ones. Rejected
> because PA may have internal/system headers that the adapter shouldn't
> touch (e.g., hop-by-hop headers managed by the engine).

**Protected headers:** The diff-based strategy MUST exclude the following
headers from modification:

- `content-length` — managed automatically by `setBodyContent()`. Allowing the
  transform spec to override it would cause a body/length mismatch.
- `transfer-encoding` — after `setBodyContent()`, the body is a complete byte
  array with a known length. If the original response used chunked transfer
  encoding, the adapter MUST remove `Transfer-Encoding` from the response
  headers. `setBodyContent()` sets a fixed-length body — chunked encoding is
  no longer applicable. PingAccess may handle this automatically, but the
  adapter ensures correctness defensively.

These headers are removed from both the "original" and "transformed" header sets
before computing the diff. This prevents the transform spec from accidentally
(or intentionally) setting incorrect content-length or transfer-encoding values.

### FR-002-02: AsyncRuleInterceptor Plugin

**Requirement:** The module MUST provide a class `MessageTransformRule` extending
`AsyncRuleInterceptorBase<MessageTransformConfig>` with:

> **Async vs Sync justification:** The SDK Javadoc recommends using
> `AsyncRuleInterceptor` only when the rule requires `HttpClient`. This adapter
> does not currently use `HttpClient`, making `RuleInterceptor` (sync) technically
> sufficient. However, `AsyncRuleInterceptorBase` is chosen because:
>
> 1. Future features (e.g., external schema validation, remote spec fetching)
>    may require HTTP callouts.
> 2. The async lifecycle (`CompletionStage<Outcome>`) composes cleanly with the
>    core engine's `TransformResult` type.
> 3. The vendor's own plugins (e.g., `PingAuthorizePolicyDecisionAccessControl`)
>    use `AsyncRuleInterceptorBase`, confirming it is the production-grade base.
>
> **Trade-off:** Slightly more complex lifecycle vs future extensibility.

1. `handleRequest(Exchange)` → `CompletionStage<Outcome>`:
   - Builds `TransformContext` via `PingAccessAdapter.buildTransformContext(exchange, null)` (FR-002-13; `status = null` for request phase).
   - Wraps request via `PingAccessAdapter.wrapRequest()`.
   - Transforms via `TransformEngine.transform(message, Direction.REQUEST, transformContext)`
     (**3-arg overload** — required for `$cookies`, `$queryParams`, `$session` injection).
   - Dispatches on `TransformResult.type()`:
     - `SUCCESS` → applies changes via `PingAccessAdapter.applyRequestChanges()`.
     - `PASSTHROUGH` → no-op (exchange untouched).
     - `ERROR` → see FR-002-11 error-mode dispatch.
   - On DENY: set `ExchangeProperty TRANSFORM_DENIED = true` on the exchange,
     build error response via `ResponseBuilder`, set on exchange, return `Outcome.RETURN`.
   - Returns `Outcome.CONTINUE` (or `Outcome.RETURN` in DENY mode on error).
2. `handleResponse(Exchange)` → `CompletionStage<Void>`:
   - **DENY guard:** If `exchange.getProperty(TRANSFORM_DENIED)` is `true`, skip
     all processing and return a completed stage immediately. This prevents
     overwriting the error response set during `handleRequest()`.
   - Builds `TransformContext` via `PingAccessAdapter.buildTransformContext(exchange, exchange.getResponse().getStatusCode())`.
   - Wraps response via `PingAccessAdapter.wrapResponse()`.
   - Transforms via `TransformEngine.transform(message, Direction.RESPONSE, transformContext)`.
   - Dispatches on `TransformResult.type()`:
     - `SUCCESS` → applies changes via `PingAccessAdapter.applyResponseChanges()`.
     - `PASSTHROUGH` → no-op.
     - `ERROR` → see FR-002-11 error-mode dispatch.
   - Returns completed `CompletionStage<Void>`.
3. `getErrorHandlingCallback()` → `ErrorHandlingCallback`:
   - **This method is abstract on `AsyncRuleInterceptor` and MUST be implemented.**
   - Returns `new RuleInterceptorErrorHandlingCallback(getTemplateRenderer(), errorConfig)`
     using the SDK's provided implementation. This callback writes a PA-formatted
     error response when an unhandled exception escapes `handleRequest()`/`handleResponse()`.

> **TemplateRenderer usage (design decision):** The adapter does NOT use
> `TemplateRenderer` for error responses. Error bodies are generated as
> RFC 9457 JSON (`application/problem+json`) directly, without PA's
> Velocity template engine. `TemplateRenderer` is only referenced
> indirectly via `RuleInterceptorErrorHandlingCallback` (which handles
> unhandled exceptions — not transform failures). Transform failures are
> handled by the adapter's own error-mode logic (FR-002-11).

> **Override note:** `AsyncRuleInterceptorBase` provides a default no-op
> implementation of `handleResponse()`. The adapter MUST override this to
> implement response-phase transformations. Without the override, response
> transforms would be silently skipped.

> **DENY guard rationale:** The SDK contract states that `RETURN` from
> `handleRequest()` does NOT skip response interceptors — it triggers them in
> reverse order (SDK guide §12). Without the DENY guard, `handleResponse()` would
> overwrite the RFC 9457 error response that `handleRequest()` set on the exchange.

**DENY ExchangeProperty declaration:**

```java
private static final ExchangeProperty<Boolean> TRANSFORM_DENIED =
    ExchangeProperty.create("io.messagexform", "transformDenied", Boolean.class);
```

| Aspect | Detail |
|--------|--------|
| Success path | Request body transformed, response body transformed, both directions independent |
| Failure path | Transform error → log warning, return original untouched exchange (ADR-0013 copy-on-wrap safety), continue pipeline |
| Error mode (request) | `PASS_THROUGH` → `Outcome.CONTINUE` with original. `DENY` → set `TRANSFORM_DENIED`, `Outcome.RETURN` with RFC 9457 error response. |
| Error mode (response) | `PASS_THROUGH` → original response untouched. `DENY` → **rewrite** the response body/status to a 502 RFC 9457 error. |
| DENY + handleResponse | `handleRequest()` DENY → `handleResponse()` called (SDK contract) → DENY guard skips processing → client receives DENY error. |
| Thread safety | Plugin instances may be shared across threads — all adapter state is method-local |
| Status | ⬜ Not yet implemented |
| Source | G-002-02 |

### FR-002-03: @Rule Annotation

**Requirement:** The plugin class MUST be annotated with:

```java
@Rule(
    category = RuleInterceptorCategory.Processing,
    destination = { RuleInterceptorSupportedDestination.Site },
    label = "Message Transform",
    type = "MessageTransform",
    expectedConfiguration = MessageTransformConfig.class
)
```

**Constraints:**
- `destination = Site` is **mandatory** — Agent rules cannot access request body
  or handle responses.
- `type = "MessageTransform"` must be unique across all PingAccess plugins.
- `category = Processing` — this is a request/response processing rule, not an
  access control rule.

| Aspect | Detail |
|--------|--------|
| Status | ⬜ Not yet implemented |
| Source | G-002-03, SDK constraint |

### FR-002-04: Plugin Configuration (Admin UI)

**Requirement:** The plugin MUST expose the following configuration fields via
`@UIElement` annotations on a `MessageTransformConfig extends SimplePluginConfiguration`:

| Field | UIElement Type | Label | Required | Default | Description |
|-------|---------------|-------|----------|---------|-------------|
| `specsDir` | TEXT | Spec Directory | Yes | `/specs` | Path to directory of transform YAML specs |
| `profilesDir` | TEXT | Profiles Directory | No | `/profiles` | Path to directory of transform profiles |
| `activeProfile` | TEXT | Active Profile | No | (empty) | Profile name to activate (empty = no profile) |
| `errorMode` | SELECT (enum: `ErrorMode`) | Error Mode | Yes | `PASS_THROUGH` | `PASS_THROUGH` or `DENY` — behaviour on transform failure |
| `reloadIntervalSec` | TEXT | Reload Interval (s) | No | `0` | **Spec YAML file** reload interval in seconds (0 = disabled, max 86400). Java type: `Integer`. See note below. |
| `schemaValidation` | SELECT (enum: `SchemaValidation`) | Schema Validation | No | `LENIENT` | `STRICT` or `LENIENT` — schema validation mode |
| `enableJmxMetrics` | CHECKBOX | Enable JMX Metrics | No | `false` | When enabled, registers JMX MBeans exposing transform counters. See FR-002-14. |

The configuration JSON maps directly to these fields via the PingAccess admin API.

> **Enum auto-discovery (SDK guide §7):** The `errorMode` and
> `schemaValidation` SELECT fields SHOULD be backed by Java enums. When a
> `SELECT` field's type is an `Enum` and no `@Option` annotations are
> provided, `ConfigurationBuilder.from()` auto-generates `ConfigurationOption`
> instances from the enum constants. This eliminates manual `@Option`
> annotations:
>
> ```java
> public enum ErrorMode {
>     PASS_THROUGH("Pass Through"),
>     DENY("Deny");
>     private final String label;
>     ErrorMode(String label) { this.label = label; }
>     @Override public String toString() { return label; }
> }
>
> public enum SchemaValidation {
>     STRICT("Strict"),
>     LENIENT("Lenient");
>     private final String label;
>     SchemaValidation(String label) { this.label = label; }
>     @Override public String toString() { return label; }
> }
> ```

> **Help text (SDK guide §7):** Each `@UIElement` field SHOULD include
> `@Help` annotations with inline documentation for administrators:
>
> | Field | Help Content |
> |-------|-------------|
> | `specsDir` | "Absolute path to the directory containing transform spec YAML files. Must not contain '..' segments." |
> | `profilesDir` | "Absolute path to the directory containing transform profile files. Leave empty to use specs without profiles." |
> | `activeProfile` | "Name of the profile to activate. Leave empty for no profile filtering." |
> | `errorMode` | "PASS_THROUGH: log errors and continue with original message. DENY: reject the request/response with an RFC 9457 error." |
> | `reloadIntervalSec` | "Interval in seconds for re-reading spec/profile files from disk. 0 = disabled (specs loaded only at startup). Maximum 86400 (24 hours)." |
>
> **`reloadIntervalSec` validation:** The field MUST have `@Min(0) @Max(86400)`
> constraints. Non-numeric input is rejected by Jakarta Bean Validation (surfaced
> in the PA admin UI as a validation error). Negative values are rejected by `@Min(0)`.
> | `schemaValidation` | "STRICT: reject specs failing JSON Schema validation. LENIENT: log warnings but accept specs." |
> | `enableJmxMetrics` | "Enable JMX MBean registration for transform metrics. When enabled, counters for success/error/passthrough transforms and latency are exposed via JMX under the `io.messagexform` domain. Requires JMX to be configured on PingAccess (see PA Monitoring Guide). Disabled by default for zero overhead." |

**`reloadIntervalSec` clarification:** This controls periodic re-reading of
transform spec YAML files from `specsDir` and profiles from `profilesDir` —
it does **not** hot-reload the adapter JAR itself (Constraint #2). When
`reloadIntervalSec > 0`, the adapter starts a `ScheduledExecutorService` with
a named daemon thread:

```java
Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "mxform-spec-reload");
    t.setDaemon(true);
    return t;
});
```

The daemon thread ensures PingAccess shutdown is not blocked by the reload
scheduler. The thread name `mxform-spec-reload` aids diagnostics in thread dumps.

**Shutdown:** The adapter MUST shut down the executor when the plugin is
decommissioned. Two strategies are applied together (belt-and-suspenders):

1. **`@PreDestroy` annotation** (preferred — orderly shutdown):
   ```java
   private ScheduledExecutorService reloadExecutor;  // set in configure()

   @PreDestroy
   void shutdown() {
       if (reloadExecutor != null) {
           reloadExecutor.shutdownNow();
       }
   }
   ```

2. **Daemon thread flag** (fallback safety net):
   Already applied via `t.setDaemon(true)` in the thread factory above.
   Ensures PA JVM shutdown is never blocked, even if `@PreDestroy` is not
   invoked (e.g., ungraceful shutdown, kill -9).

> **Note:** The `reloadExecutor` field is set once in `configure()` and
> never reassigned — it is safe for concurrent reads (same thread-safety
> model as other init-time fields per NFR-002-03).
The executor calls `TransformEngine.reload()` at the specified interval. This
allows ops to update transform specs on disk without restarting PingAccess.
When `reloadIntervalSec = 0` (default), specs are loaded only once during
`configure()` and changes require a PA restart.

**Reload failure semantics:** If `TransformEngine.reload()` throws during a
scheduled reload (e.g., malformed YAML, file I/O error), the adapter logs a
warning and retains the previous valid `TransformRegistry`. The failed reload
does NOT disrupt in-flight requests — `TransformEngine`'s `AtomicReference`-
based swap ensures that the old registry remains active until a successful
reload replaces it (NFR-001-05).

**Security note:** The `specsDir` and `profilesDir` fields accept arbitrary
file system paths. While PingAccess admin access is a privileged context,
the adapter SHOULD validate these paths in `configure()` as defense-in-depth:
- Reject paths containing `..` segments.
- Log the resolved absolute path at INFO level for audit trail.
- Verify the directory exists and is readable.

| Aspect | Detail |
|--------|--------|
| Success path | Admin creates rule → specifies spec directory → plugin loads specs on `configure()` |
| Validation path | Missing `specsDir` → `ValidationException` on `configure()` |
| Config discovery | Annotation-driven: `ConfigurationBuilder.from(Config.class)` (recommended, used by adapter) |
| Status | ⬜ Not yet implemented |
| Source | G-002-03 |

> **Configuration field discovery patterns** (see Appendix D for details):
> 1. **Annotation-driven** — `ConfigurationBuilder.from(Config.class)` auto-discovers `@UIElement`-annotated fields. Used by `Clobber404ResponseRule`, `RiskAuthorizationRule`.
> 2. **Programmatic** — `new ConfigurationBuilder().configurationField(...)` with manual field registration, options, help text, and sub-fields. Used by `ParameterRule`.
> 3. Both patterns compose with `ErrorHandlerUtil.getConfigurationFields()` via `.addAll()` to append standard error handler config.

### FR-002-05: Plugin Lifecycle

**Requirement:** The plugin MUST initialize and manage the core engine through
the PingAccess plugin lifecycle (official 7-step sequence from SDK guide §1):

1. **Annotation interrogation:** The `@Rule` annotation on `MessageTransformRule`
   is read to determine that `MessageTransformConfig` is the configuration class.
2. **Spring initialization:** Both the `MessageTransformRule` and
   `MessageTransformConfig` beans are provided to Spring for autowiring
   (`@Inject` setter injection) and `@PostConstruct` initialization.

   > ⚠️ **Order is undefined:** The order in which the rule and its
   > configuration are processed by Spring is **not defined**. Do not assume
   > the config is available before `configure()` is called.

3. **Name assignment:** `MessageTransformConfig.setName(String)` is called with
   the administrator-defined rule name.
4. **JSON → Configuration mapping:** PingAccess maps the incoming JSON
   configuration to the `MessageTransformConfig` instance via Jackson.

   > ⚠️ **All-fields gotcha:** The JSON plugin configuration **must contain a
   > JSON member for each field**, regardless of implied value. Failure to
   > include a field — even if it has a Java default — can lead to errors.
   > This affects the PA admin REST API contract: when creating/updating the
   > rule, all config fields must be present in the JSON payload.

5. **Bean Validation:** `Validator.validate()` is invoked on the
   `MessageTransformConfig`. Since PA 5.0+, Bean Validation runs **before**
   `configure()` is called — `@NotNull`, `@Size`, and other constraint
   annotations on config fields are guaranteed to be enforced before the
   plugin receives them.
6. **`configure()` call:** `configure(MessageTransformConfig)` is called. At
   this point, all config fields have already passed Bean Validation. The
   plugin MUST:
   - Initialize `SpecParser` with JSLT expression engine.
   - Initialize `TransformEngine`.
   - Load all specs from `specsDir`.
   - Optionally load profiles from `profilesDir`.
   The adapter SHOULD NOT duplicate constraint checks in `configure()` that
   are already covered by Bean Validation annotations.
7. **Available:** The instance is made available to service end-user requests
   via `handleRequest(Exchange)` and `handleResponse(Exchange)`.

**Teardown:** No explicit destroy lifecycle. Plugins are garbage-collected on
PA restart. For plugins with background threads (e.g., spec reload scheduler
in FR-002-04), use `@PreDestroy` to release resources cleanly. The daemon
thread flag provides a fallback safety net. See SDK guide §1.

> **Injection constraints (SDK guide §1, definitive closed list):**
> Only **3 classes** are available for injection into plugins:
>
> | # | Class | Purpose |
> |---|-------|---------|
> | 1 | `TemplateRenderer` | Renders HTML error/response templates |
> | 2 | `ThirdPartyServiceModel` | Handle to a third-party service |
> | 3 | `HttpClient` | Async HTTP client for third-party calls |
>
> No other PA-internal classes can be injected. Use `jakarta.inject.Inject`
> — **NOT** Spring's `@Autowired` — to protect against PA internal changes.
> (Verified: PA SDK 9.0.1.0 bytecode uses `jakarta.inject.Inject`.)
> `AsyncRuleInterceptorBase` already pre-wires `HttpClient` and
> `TemplateRenderer` via setter injection, so explicit `@Inject` is only
> needed if implementing the raw SPI interface without using the base class.

| Aspect | Detail |
|--------|--------|
| Success path | `configure()` initializes engine + loads specs without error |
| Failure path | Spec parse error → `ValidationException` with detail message → plugin not activated |
| Status | ⬜ Not yet implemented |
| Source | G-002-05, SDK lifecycle docs |

### FR-002-06: Session Context Binding (Identity)

**Requirement:** The adapter MUST populate the `sessionContext` field of the
`Message` record from the PingAccess `Identity` object (ADR-0030, FR-001-13).

**Design principle:** The `$session` JsonNode is a **flat merged object** that
combines all identity sources into a single hierarchy. This allows JSLT
expressions to access any session attribute uniformly (e.g., `$session.email`,
`$session.clientId`, `$session.authzCache`) without navigating nested sub-objects.

#### Merge Layers (lowest to highest precedence)

The adapter merges four sources into a single flat `ObjectNode`. Later layers
override earlier layers on key collision:

| Layer | Source | Contents | Precedence |
|-------|--------|----------|------------|
| 1 (base) | `Identity` getters | `subject`, `mappedSubject`, `trackingId`, `tokenId`, `tokenExpiration` | Lowest |
| 2 | `OAuthTokenMetadata` | `clientId`, `scopes`, `tokenType`, `realm`, `tokenExpiresAt`, `tokenRetrievedAt` | |
| 3 | `Identity.getAttributes()` | OIDC claims / token introspection (spread into flat object) | |
| 4 (top) | `SessionStateSupport.getAttributes()` | Dynamic session state (spread into flat object) | Highest — can override any key |

> **Why this precedence?** Session state (layer 4) is the most dynamic — rules
> can write to it during the session. It should have the highest precedence so
> that a rule like `ExternalAuthorizationRule` can override claim values by
> storing updated attributes. OIDC claims (layer 3) override PA identity fields
> (layer 1) because claims are the authoritative token data — PA identity fields
> like `subject` are convenience wrappers that may already be in the claims as `sub`.

> **Implementation pattern:** See
> [`docs/architecture/features/002/pingaccess-sdk-guide.md` §6 "Building $session"](pingaccess-sdk-guide.md#building-session--flat-merge-pattern)
> for the complete `buildSessionContext()` implementation.
>
> **No boundary conversion needed (ADR-0031):** PA uses a flat classpath —
> `lib/*` and `deploy/*` share the same `AppClassLoader`. Jackson is
> PA-provided (`compileOnly`), so `Identity.getAttributes()` returns the
> same `JsonNode` class the adapter uses. Direct `session.set(key, paNode)`
> works without serialization round-trips.

#### `$session` Schema (flat)

| Path | Type | Source Layer | Notes |
|------|------|-------------|-------|
| `$session.subject` | string | L1: `Identity.getSubject()` | Authenticated principal (may be overridden by OIDC `sub` in L3) |
| `$session.mappedSubject` | string | L1: `Identity.getMappedSubject()` | Mapped by IdentityMapping plugin |
| `$session.trackingId` | string | L1: `Identity.getTrackingId()` | PA-generated tracking ID |
| `$session.tokenId` | string | L1: `Identity.getTokenId()` | OAuth token ID |
| `$session.tokenExpiration` | string (ISO) | L1: `Identity.getTokenExpiration()` | Omitted if no expiration |
| `$session.clientId` | string | L2: `OAuthTokenMetadata.getClientId()` | OAuth client ID |
| `$session.scopes` | array | L2: `OAuthTokenMetadata.getScopes()` | Granted scopes |
| `$session.tokenType` | string | L2: `OAuthTokenMetadata.getTokenType()` | e.g., "Bearer" |
| `$session.realm` | string | L2: `OAuthTokenMetadata.getRealm()` | OAuth realm |
| `$session.tokenExpiresAt` | string (ISO) | L2: `OAuthTokenMetadata.getExpiresAt()` | Token expiration |
| `$session.tokenRetrievedAt` | string (ISO) | L2: `OAuthTokenMetadata.getRetrievedAt()` | When token was fetched |
| `$session.sub` | string | L3: OIDC claim | Standard OIDC subject claim |
| `$session.email` | string | L3: OIDC claim | User email |
| `$session.roles` | array | L3: OIDC claim | Custom roles claim |
| `$session.*` | any | L3: (any OIDC claim) | All claims spread into flat namespace |
| `$session.<key>` | any | L4: `SessionStateSupport` | Dynamic session state — can override any key |

> **Example JSLT usage** with flat `$session`:
>
> ```jslt
> // Scope-based conditional transform
> if (contains($session.scopes, "admin"))
>   { "user": $session.email, "role": "admin" }
> else
>   { "user": $session.sub, "role": "viewer" }
>
> // Access cached authorization from SessionStateSupport
> let authz = $session.authzDecision
> if ($authz == "PERMIT") ...
> ```

**Collision handling:** Key collisions are expected and intentional. Common
collisions:
- `subject` (L1) vs `sub` (L3 OIDC) — both refer to the user principal but
  use different keys, so no actual collision. If a custom claim is named
  `subject`, the OIDC value (L3) wins.
- Session state (L4) overriding any earlier value is the primary use case —
  e.g., a rule sets `authzLevel` in session state, and JSLT reads the latest.

**Null identity (unauthenticated):** If `exchange.getIdentity() == null`,
`sessionContext` is `null` → `$session` evaluates to `null` in JSLT.
Transforms MUST gracefully handle null `$session` (e.g., `if ($session != null) ...`).

**Trust model:** The `$session` variable exposes the full identity context
(subject, claims, OAuth metadata, session state) to JSLT expressions. This is
by design — the adapter acts as a **trusted bridge** between PingAccess's
identity subsystem and the transform engine.

**Implications:**
- Transform spec authors are trusted actors. A malicious spec could leak
  sensitive identity data (e.g., `$session.tokenId`) into the response body.
- The core engine's `sensitive` list (ADR-0019) can redact specific output
  paths, but cannot prevent JSLT from **reading** session data.
- In production, restrict write access to `specsDir` to authorized operators.

**Future consideration:** A `SessionContextFilter` that selectively exposes
only whitelisted `$session` fields could be added as a follow-up feature if
multi-tenant spec authoring becomes a requirement.

| Aspect | Detail |
|--------|--------|
| Success path | `$session.email` resolves to OIDC claim, `$session.clientId` to OAuth client, `$session.authzCache` to dynamic state |
| Failure path | No identity (unauthenticated) → `sessionContext = null` → `$session` is `null` in JSLT |
| Collision path | L4 session state key overrides L3 OIDC claim key (by design — dynamic > static) |
| Status | ⬜ Not yet implemented |
| Source | ADR-0030, FR-001-13, G-002-01 |

### FR-002-07: ExchangeProperty State (cross-rule data)

**Requirement:** The adapter MUST store a summary of the transform result
(match status, spec id, transform duration) as an `ExchangeProperty` on the
`Exchange`, enabling downstream rules or logging to inspect transform metadata.

```java
private static final ExchangeProperty<TransformResultSummary> TRANSFORM_RESULT =
    ExchangeProperty.create("io.messagexform", "transformResult",
                            TransformResultSummary.class);
```

> **`TransformResultSummary` is an adapter-local record** (not the core
> `TransformResult`) containing only primitive/String fields for simplicity
> and zero-dependency cross-rule state sharing.
>
> ⚠️ **Namespace uniqueness:** Per SDK §2, `ExchangeProperty` equality is based
> on namespace + identifier only (type parameter is ignored). All adapter-defined
> properties use namespace `io.messagexform` with unique identifiers
> (`transformDenied`, `transformResult`). Plugin authors extending this adapter
> MUST NOT reuse these identifiers with different types.

**`TransformResultSummary` schema:**

| Field | Type | Description |
|-------|------|-------------|
| `specId` | `String` | Matched spec ID (null if no match) |
| `specVersion` | `String` | Matched spec version (null if no match) |
| `direction` | `String` | `"REQUEST"` or `"RESPONSE"` |
| `durationMs` | `long` | Transform duration in milliseconds |
| `outcome` | `String` | `"SUCCESS"`, `"PASSTHROUGH"`, or `"ERROR"` |
| `errorType` | `String` | Error type code (null if no error) |
| `errorMessage` | `String` | Human-readable error message (null if no error) |

> **Field sourcing:** `specId` and `specVersion` are populated from the
> `TransformSpec` matched during profile resolution. The adapter tracks the
> matched spec metadata from the `TransformRegistry` lookup (which returns
> the matched spec alongside the result) and attaches it to the summary.
> The core engine's `TransformResult` itself does not expose spec identification
> — the adapter is responsible for correlating the result with its source spec.

**Usage by downstream rules:**
```java
exchange.getProperty(TRANSFORM_RESULT).ifPresent(summary -> {
    LOG.info("Transform: spec={}, outcome={}, duration={}ms",
             summary.specId(), summary.outcome(), summary.durationMs());
});
```

| Aspect | Detail |
|--------|--------|
| Success path | Downstream rule reads `exchange.getProperty(TRANSFORM_RESULT)` → `TransformResultSummary` with populated fields |
| Status | ⬜ Not yet implemented |
| Source | SDK ExchangeProperty pattern |

### FR-002-08: SPI Registration

**Requirement:** The module MUST include a
`META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor` file
containing the fully qualified class name of the plugin:

```
io.messagexform.pingaccess.MessageTransformRule
```

This enables PingAccess's `ServiceLoader` discovery.

| Aspect | Detail |
|--------|--------|
| Validation path | `jar tf adapter-pingaccess-*.jar | grep META-INF/services` → shows the SPI file |
| Status | ⬜ Not yet implemented |
| Source | G-002-04, SDK SPI pattern |

### FR-002-09: Deployment Packaging

**Requirement:** The module MUST produce a **shadow JAR** containing:

- The adapter classes (`io.messagexform.pingaccess.*`)
- The core engine (`io.messagexform.core.*`)
- `META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor`
- All runtime dependencies (Jackson, JSLT, SnakeYAML, JSON Schema Validator)

The shadow JAR MUST **exclude** the PingAccess SDK classes — those are provided
by the PA runtime (`/opt/server/lib/pingaccess-sdk-*.jar`).

**PA-provided dependencies (ADR-0031):** PingAccess uses a **flat classpath**
— `lib/*` and `deploy/*` share the same JVM application classloader. Libraries
in `/opt/server/lib/` are directly visible to plugin code. The following MUST
be declared `compileOnly` and **not** bundled in the shadow JAR:

| Library | PA 9.0.1 Version | Notes |
|---------|-----------------|-------|
| `jackson-databind` | 2.17.0 | SDK API returns `JsonNode` — same class shared |
| `jackson-core` | 2.17.0 | Transitive |
| `jackson-annotations` | 2.17.0 | `@JsonProperty` on config classes |
| `slf4j-api` | 1.7.36 | PA's SLF4J provider is **Log4j2** (not Logback) |
| `jakarta.validation-api` | 3.1.1 | Bean Validation |
| `jakarta.inject-api` | 2.0.1 | CDI |
| PingAccess SDK | 9.0.1.0 | Plugin API |

> **No Jackson relocation.** Jackson is PA-provided and shared. Relocating
> Jackson would cause `ClassCastException` because `Identity.getAttributes()`
> returns `com.fasterxml.jackson.databind.JsonNode` but relocated code would
> expect `io.messagexform.shaded.jackson.databind.JsonNode` — different
> classes within the same classloader.
>
> **No boundary conversion.** Since PA and the adapter share the same Jackson
> classes, `Identity.getAttributes()` returns the same `JsonNode` class.
> Direct `session.set(key, paNode)` works. No serialization round-trip.
>
> **No dual ObjectMapper.** A single `ObjectMapper` instance handles all
> adapter JSON operations.
>
> **Evidence:** Verified via static analysis of PA 9.0.1 (`run.sh` classpath
> construction, `javap` decompilation of `ServiceFactory`, `Bootstrap`,
> `ConfigurablePluginPostProcessor`). See ADR-0031.

**SnakeYAML:** Not shipped by PA — MUST be bundled in the shadow JAR as an
`implementation` dependency.

**TelemetryListener:** The PA adapter does NOT register a custom
`TelemetryListener` implementation. The core engine's built-in SLF4J logging
is sufficient. PA's own monitoring (PingAccess admin → audit logs) captures
rule execution events. Custom telemetry can be added as a follow-up if needed.

The JAR MUST be deployable by copying to `<PA_HOME>/deploy/` (Docker:
`/opt/server/deploy/`).

| Aspect | Detail |
|--------|--------|
| Success path | `cp adapter-pingaccess-*.jar /opt/server/deploy/ && restart PA` → plugin visible in admin UI |
| Validation path | `./gradlew :adapter-pingaccess:shadowJar` produces a single JAR < 5 MB (no bundled Jackson/SLF4J) |
| Status | ⬜ Not yet implemented |
| Source | G-002-04 |

### FR-002-10: Gradle Module Setup

**Requirement:** The `adapter-pingaccess` module MUST be configured as a standard
Gradle subproject with:

1. Dependency on `project(":core")` (implementation).
2. Dependency on `com.pingidentity.pingaccess:pingaccess-sdk:9.0.1.0`
   (**compileOnly** — provided by PA runtime).
3. Dependency on `jakarta.validation:jakarta.validation-api:3.1.1` (compileOnly).

   > **Version compatibility note:** PA 9.0 uses `jakarta.validation`
   > (confirmed from SDK bytecode: `jakarta.validation.ValidationException`).
   > The 3.1.1 API is backwards-compatible with 3.0.x. If the PA runtime ships
   > an older `validation-api` (e.g., 3.0.0 with Hibernate Validator 7.0.5),
   > the `compileOnly` scope ensures only PA’s bundled version is used at
   > runtime. Verify by checking `<PA_HOME>/lib/` for the actual
   > `jakarta.validation-api-*.jar` version.
4. Dependency on `jakarta.inject:jakarta.inject-api:2.0.1` (compileOnly).
5. Shadow JAR plugin configured to exclude the `compileOnly` dependencies.
6. Java 21 toolchain (same as core).
7. The PingAccess Maven repository
   (`http://maven.pingidentity.com/release/`) added as a repository. Fallback:
   local JAR at `docs/reference/pingaccess-sdk/pingaccess-sdk-9.0.1.0.jar` via
   `files()` dependency if the Maven repo is unreachable.

   > **HTTP not HTTPS:** The Ping Identity Maven repository uses HTTP per the
   > official SDK documentation. If your organization requires HTTPS-only
   > repositories, mirror the SDK artifacts to an internal repository manager.

| Aspect | Detail |
|--------|--------|
| Success path | `./gradlew :adapter-pingaccess:build` compiles and passes tests |
| Status | ⬜ Not yet implemented |
| Source | G-002-04, FR-009-01 (Gradle governance) |

### FR-002-11: Error Handling

**Requirement:** The adapter MUST handle transform errors according to the
configured error mode. Transform errors include: JSLT evaluation failures,
spec parse errors at runtime, eval budget exceeded, and output size exceeded.

| Error Mode | handleRequest Behaviour | handleResponse Behaviour |
|------------|------------------------|--------------------------|
| `PASS_THROUGH` | Log warning, return `Outcome.CONTINUE` (original untouched request continues to backend) | Log warning, leave original response untouched |
| `DENY` | Build error response via `ResponseBuilder` + `exchange.setResponse()`, return `Outcome.RETURN` to halt pipeline | **Rewrite** response body/status to 502 with RFC 9457 error in-place |

**Key constraint:** During `handleRequest()`, `exchange.getResponse()` is
**null** — the adapter MUST use `ResponseBuilder` to construct a new `Response`.
See [SDK guide §8](pingaccess-sdk-guide.md#8-responsebuilder--error-handling)
for complete code patterns and the rationale for rejecting `AccessException`.

The adapter MUST NOT throw `AccessException` for transform failures — transform
errors are non-fatal by default (adapter continues the pipeline). Unhandled
exceptions are caught by `getErrorHandlingCallback()` (FR-002-02).

| Aspect | Detail |
|--------|--------|
| Success path (PASS_THROUGH) | Malformed JSON body → log + continue with original body |
| Success path (DENY, request) | JSLT error → build Response via `ResponseBuilder` + `exchange.setResponse()` → `Outcome.RETURN` |
| Success path (DENY, response) | JSLT error → rewrite response body/status to 502 error in-place |
| Status | ⬜ Not yet implemented |
| Source | ADR-0022, ADR-0024 |

> **PA-native error handler integration (design decision):** The adapter does
> NOT use `ErrorHandlerUtil.getConfigurationFields()` or
> `ErrorHandlerConfigurationImpl`. **Rationale:** Our error responses use
> RFC 9457 `application/problem+json` format (ADR-0022, ADR-0024), which is
> semantically different from PA's built-in HTML template-based error pages.
> Mixing both would confuse administrators — the PA error handler fields
> (template file, content type, status message) would be misleading when the
> adapter always produces JSON. If PA-native error templates are desired in
> the future, this can be added as a follow-up by appending
> `ErrorHandlerUtil.getConfigurationFields()` to `getConfigurationFields()`
> and adding a `@JsonUnwrapped @Valid` error handler config field (see SDK
> guide §7 for the pattern).

> **`handleResponse` DENY error body clarification (design decision):**
> In `handleResponse()` DENY mode, the error body comes from one of two paths:
>
> 1. **Normal path:** `TransformResult.errorResponse()` — the core engine
>    produces an RFC 9457 `JsonNode` error body. The adapter serializes it via
>    `objectMapper.writeValueAsBytes(result.errorResponse())`, sets it as the
>    response body via `exchange.getResponse().setBodyContent()`, overwrites
>    the status to 502, and sets Content-Type to `application/problem+json`.
> 2. **Wrap-failure path:** If `wrapResponse()` itself fails (e.g., `IOException`
>    during `body.read()`), the adapter constructs its own RFC 9457 error body
>    with `type: urn:messagexform:error:adapter:wrap-failure` and status 502.
>    This error body is built by the adapter, not the core engine.

### FR-002-12: Docker E2E Test Script

**Requirement:** The project MUST include a script (`scripts/pa-e2e-test.sh`)
that:

1. Builds the adapter shadow JAR via Gradle.
2. Starts a PingAccess 9.0 Docker container
   (`pingidentity/pingaccess:9.0.1-latest`) with:
   - The adapter JAR mounted into `/opt/server/deploy/`.
   - A license file mounted into `/opt/out/instance/conf/`.
   - Test transform specs mounted into a spec directory.
   - A test site configured (mock backend via a simple HTTP echo server).
3. Waits for PingAccess to start and be healthy.
4. Configures a rule + application + resource via the PA Admin REST API
   (port 9000).
5. Sends test requests through the PA engine (port 3000) and verifies
   transformed responses.
6. Tears down the Docker containers.

**Note:** This script is planned for implementation **after** the adapter
code is complete. It is listed here for traceability.

| Aspect | Detail |
|--------|--------|
| Success path | `./scripts/pa-e2e-test.sh` → builds + configures PA + runs assertions + exits 0 |
| Status | ⬜ Not yet implemented (planned, implementation deferred) |
| Source | G-002-05 |

### FR-002-13: TransformContext Construction

**Requirement:** The adapter MUST build a `TransformContext` from the PingAccess
`Exchange` and pass it to the 3-arg `TransformEngine.transform()` overload.
Without this, JSLT variables `$cookies`, `$queryParams`, `$headers`, `$status`,
and `$session` would be null/empty.

The adapter MUST provide a `buildTransformContext(Exchange)` method (not part
of the `GatewayAdapter` SPI — adapter-specific helper) that maps:

| TransformContext Field | Source | Notes |
|------------------------|--------|-------|
| `headers` | `exchange.getRequest().getHeaders().asMap()` → flatten to single-value map (first value per name, lowercase keys) | Same normalization pattern as `wrapRequest` |
| `headersAll` | `exchange.getRequest().getHeaders().asMap()` → convert to multi-value map | Same normalization pattern |
| `status` | `null` for REQUEST direction, `exchange.getResponse().getStatusCode()` for RESPONSE | Set by the caller (`MessageTransformRule`) based on direction |
| `queryParams` | `exchange.getRequest().getQueryStringParams()` → flatten `Map<String, String[]>` to `Map<String, String>` using first-value semantics | Values are URL-decoded by the PA SDK. On `URISyntaxException`: log warning, use empty map. |
| `cookies` | `exchange.getRequest().getHeaders().getCookies()` → flatten `Map<String, String[]>` to `Map<String, String>` using first-value semantics | Cookie values are URL-decoded |
| `sessionContext` | See FR-002-06 (`Exchange.getIdentity()` → build `JsonNode`) | `null` if no identity (unauthenticated) |

**Query param multi-value handling:** PingAccess's `getQueryStringParams()` returns
`Map<String, String[]>`. The adapter uses **first-value semantics** (take `values[0]`
for each key), matching the `StandaloneAdapter` pattern (FR-004-39). Multi-value
query params are available in full via `$headers_all` if the gateway transmits them
as headers, but the `$queryParams` variable uses single-value.

**`URISyntaxException` handling:** `Request.getQueryStringParams()` can throw
`URISyntaxException` for malformed URIs. On this exception, the adapter logs a
warning and returns an empty `queryParams` map. The transform proceeds with
`$queryParams` as an empty object — JSLT expressions referencing query params
evaluate to `null` gracefully.

**Lifecycle note:** `TransformContext` is an immutable record (`public record
TransformContext(...)`). Two instances are built per exchange:

1. **Request phase:** `buildTransformContext(exchange, null)` — `status` is
   `null` (no response yet).
2. **Response phase:** `buildTransformContext(exchange, exchange.getResponse().getStatusCode())`
   — `status` is the response status code.

To avoid re-parsing cookies and query params in the response phase, the adapter
MAY cache the parsed `cookies` and `queryParams` maps as method-local variables
in `handleRequest()` and pass them to `handleResponse()` via an `ExchangeProperty`.
Alternatively, re-parsing is acceptable (cost is negligible for typical request
sizes).

| Aspect | Detail |
|--------|--------|
| Success path | `TransformContext` populated with headers, cookies, query params, session → JSLT `$cookies.sessionToken` resolves correctly |
| Failure path (URI) | Malformed query string → empty `queryParams` → JSLT `$queryParams.page` evaluates to `null` |
| Failure path (no identity) | Unauthenticated → `sessionContext = null` → `$session` is `null` in JSLT |
| Status | ⬜ Not yet implemented |
| Source | G-002-01, FR-004-37 (StandaloneAdapter reference pattern) |

### FR-002-14: JMX Observability (Opt-in)

**Requirement:** When `enableJmxMetrics = true`, the adapter MUST register a
JMX MBean exposing aggregate transform metrics. When `enableJmxMetrics = false`
(default), no MBean is registered and there is **zero performance overhead**.

**Design rationale:** PingAccess's official Monitoring Guide recommends JMX
MBeans as the primary mechanism for resource metrics (reference:
`pingaccess-9.0.txt` §"Resource metrics", page 835). PA administrators already
use JConsole and Splunk for JMX-based monitoring. Registering custom MBeans
under a plugin-specific domain (`io.messagexform`) integrates naturally with
this existing workflow. No SDK support is needed — plugins run in the same JVM
and can use `java.lang.management.ManagementFactory` directly.

**MBean Interface:**

```java
public interface MessageTransformMetricsMXBean {
    // --- Counters ---
    long getTransformSuccessCount();   // Successful transforms (body modified)
    long getTransformPassthroughCount(); // PASSTHROUGH results (no match / no-op)
    long getTransformErrorCount();     // Transform failures (JSLT error, budget exceeded)
    long getTransformDenyCount();      // DENY outcomes (errorMode=DENY + failure)
    long getTransformTotalCount();     // Sum of all above

    // --- Spec reload ---
    long getReloadSuccessCount();      // Successful spec/profile reloads
    long getReloadFailureCount();      // Failed reloads (malformed YAML)
    long getActiveSpecCount();         // Current number of loaded specs

    // --- Latency (milliseconds) ---
    double getAverageTransformTimeMs(); // Rolling average transform duration
    long getMaxTransformTimeMs();       // Max transform duration since startup
    long getLastTransformTimeMs();      // Most recent transform duration

    // --- Reset ---
    void resetMetrics();               // Admin operation: zero all counters
}
```

**Implementation pattern:**

```java
// In configure() — register MBean if enabled
if (config.isEnableJmxMetrics()) {
    MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    ObjectName name = new ObjectName(
        "io.messagexform:type=TransformMetrics,instance=" + config.getName());
    if (!mbs.isRegistered(name)) {
        mbs.registerMBean(this.metrics, name);
    }
    this.jmxObjectName = name;
}

// In destroy/cleanup — unregister MBean
if (this.jmxObjectName != null) {
    MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    if (mbs.isRegistered(this.jmxObjectName)) {
        mbs.unregisterMBean(this.jmxObjectName);
    }
}
```

**ObjectName convention:**
`io.messagexform:type=TransformMetrics,instance=<pluginInstanceName>`

where `<pluginInstanceName>` is `config.getName()` from `PluginConfiguration`.
This allows multiple rule instances on the same PA engine to have distinct
MBeans.

**Thread safety:** All counters use `java.util.concurrent.atomic.LongAdder`
for lock-free, contention-free concurrent updates. The rolling average uses
a `LongAdder` for total time and divides by total count. `resetMetrics()`
resets all adders to zero — this is idempotent and safe to call concurrently.

**Lifecycle:**

| Event | Action |
|-------|--------|
| `configure()` with `enableJmxMetrics=true` | Register MBean. If MBean already registered (reconfigure), skip. |
| `configure()` with `enableJmxMetrics=false` | Unregister MBean if previously registered. |
| Plugin unload (PA shutdown / undeploy) | Unregister MBean in cleanup. |
| `handleRequest()` / `handleResponse()` | Increment counters after each transform. No-op if metrics disabled. |

**Audit logging (always on):** Regardless of `enableJmxMetrics`, the adapter
MUST log transform results at INFO level via SLF4J. PingAccess routes SLF4J
output to `pingaccess.log`, and the engine itself logs per-transaction timing
in `pingaccess_engine_audit.log`. This provides baseline observability with
zero configuration.

> **PA monitoring integration:** PA exposes its own JMX MBeans when
> `pa.mbean.site.connection.pool.enable=true`. PA administrators using
> JConsole will see our `io.messagexform` domain alongside the PA platform
> MBeans. Splunk users can collect both PA and adapter metrics through the
> PingAccess for Splunk app (Splunkbase #5368).

| Aspect | Detail |
|--------|--------|
| Success path | `enableJmxMetrics=true` → MBean visible in JConsole under `io.messagexform` → counters increment on each request |
| Status | ⬜ Not yet implemented |
| Source | PA Monitoring Guide (§Resource metrics), Issue 19 (spec review) |

---

## Non-Functional Requirements

| ID | Requirement | Driver | Measurement | Dependencies | Source |
|----|-------------|--------|-------------|--------------|--------|
| NFR-002-01 | Adapter transform latency MUST add < 10 ms overhead beyond the core engine transform time for a typical JSON body (< 64 KB). | Gateway SLA | Measure via `ExchangeProperty` timestamps (creation time vs. transform completion). | Core engine performance. | Spec. |
| NFR-002-02 | Shadow JAR size MUST be < 5 MB (Jackson, SLF4J, Jakarta excluded — PA-provided per ADR-0031). | Deployment ergonomics | `ls -lh adapter-pingaccess-*.jar` | Shadow plugin, dependency management. | ADR-0031. |
| NFR-002-03 | Adapter MUST be thread-safe — no mutable **per-request** state. Framework-injected fields (`@Inject`-set `ObjectMapper`, `TemplateRenderer`, `HttpClient`) and `configuration` are set once during initialization and are safe for concurrent read access. All per-request state (adapted messages, transform results, error bodies) MUST be method-local. | PA runtime constraint | Code review, ArchUnit rule (FR-009-12). | Core engine thread safety. | SDK docs. |
| NFR-002-04 | Adapter MUST NOT use reflection — same no-reflection policy as core (FR-009-12 Rule 3). | AOT compatibility | ArchUnit test. | ArchUnit (FR-009-12). | Project constitution. |
| NFR-002-05 | Adapter MUST compile with Java 21 toolchain and `-Xlint:all -Werror`. | Build consistency | `./gradlew :adapter-pingaccess:compileJava` | FR-009-02, FR-009-03. | FR-009-03. |

---

## Branch & Scenario Matrix

| Scenario ID | Description / Expected Outcome |
|-------------|-------------------------------|
| S-002-01 | **Request body transform:** POST request with JSON body → JSLT transforms body → PingAccess forwards transformed body to backend. |
| S-002-02 | **Response body transform:** Backend returns JSON → JSLT transforms response body → client receives transformed JSON. |
| S-002-03 | **Bidirectional transform:** Both request and response transformed in a single round-trip (same spec with `request` + `response` directions). |
| S-002-04 | **Header transform:** Request headers added/removed/renamed before forwarding; response headers modified before returning to client. |
| S-002-05 | **Status code transform:** Backend returns 404 → spec maps to 200 with synthetic body. |
| S-002-06 | **URL rewrite:** Request path rewritten by spec before forwarding to backend (ADR-0027). |
| S-002-07 | **Empty body:** Request with no body → NullNode → transform skipped (no match) → original forwarded. |
| S-002-08 | **Non-JSON body:** Request with `text/plain` body → body parses to NullNode → headers-only transform possible. |
| S-002-09 | **Profile matching:** Only transform requests matching active profile path pattern `/api/v1/*`. |
| S-002-10 | **No matching spec:** Request doesn't match any spec → pass-through, no transformation applied. |
| S-002-11 | **Error mode PASS_THROUGH:** Spec expression throws → log warning → continue with original body. |
| S-002-12 | **Error mode DENY:** Spec expression throws → return 502 with RFC 9457 error body. |
| S-002-13 | **Session context in JSLT:** JSLT expression uses `$session.subject` → resolves from PingAccess Identity. |
| S-002-14 | **No identity (unauthenticated):** Exchange has no Identity → `$session` is null → JSLT gracefully handles. |
| S-002-15 | **Multiple specs loaded:** Two specs match different paths → correct spec applied per request path. |
| S-002-16 | **Large body (64 KB):** Transform completes within eval budget, no OOM. |
| S-002-17 | **Plugin configuration via admin UI:** Admin creates MessageTransform rule, specifies spec directory → plugin loads specs. |
| S-002-18 | **Invalid spec directory:** `specsDir` doesn't exist → `ValidationException` on `configure()`. |
| S-002-19 | **Plugin SPI registration:** JAR deployed to `/opt/server/deploy/` → PA restart → "Message Transform" rule type visible in admin. |
| S-002-20 | **Thread safety:** Concurrent requests through the same rule instance → no data corruption. |
| S-002-21 | **ExchangeProperty metadata:** Downstream rule reads transform result metadata (spec id, duration). |
| S-002-22 | **Cookie access in JSLT:** JSLT expression uses `$cookies` → resolves from PA request cookies via `TransformContext.cookiesAsJson()`. |
| S-002-23 | **Query param access in JSLT:** JSLT expression uses `$queryParams` → resolves from PA query string via `TransformContext.queryParamsAsJson()`. |
| S-002-24 | **Shadow JAR correctness:** Deploy shadow JAR → no `ClassNotFoundException` at runtime (all deps bundled, PA SDK excluded). |
| S-002-25 | **OAuth context in JSLT:** JSLT expression uses `$session.clientId` and `$session.scopes` → resolves from `OAuthTokenMetadata` (flat `$session` namespace, see FR-002-06 merge layers). |
| S-002-26 | **Session state in JSLT:** JSLT expression uses `$session.authzCache` → resolves from `SessionStateSupport` (flat `$session` namespace, layer 4 merge). |
| S-002-27 | **Prior rule URI rewrite:** Upstream ParameterRule rewrites `/old/path` to `/new/path` → MessageTransformRule wraps using `Request.getUri()` (rewritten URI) → spec matches on `/new/path` (validates URI choice per FR-002-01 note). |
| S-002-28 | **DENY + handleResponse interaction:** `handleRequest()` returns `Outcome.RETURN` with DENY error body → `handleResponse()` is called (SDK contract) → adapter checks `TRANSFORM_DENIED` ExchangeProperty → skips response processing → client receives original DENY error (GAP-4 fix). |
| S-002-29 | **Spec hot-reload (success):** `reloadIntervalSec=30` → spec YAML modified on disk → next poll reloads specs → new spec matches next request → transform uses updated spec. |
| S-002-30 | **Spec hot-reload (failure):** `reloadIntervalSec=30` → malformed spec written to disk → reload fails with warning log → previous specs remain active → existing transforms unaffected. |
| S-002-31 | **Concurrent reload during active transform:** Reload swaps `AtomicReference<TransformRegistry>` while a transform is in flight → in-flight transform completes using its snapshot of the old registry (Java reference semantics guarantee this) → next request uses the new registry. Outcome: no data corruption, no locking, no request failure. Test: trigger reload in a background thread while a slow transform is executing. |
| S-002-32 | **Non-JSON response body:** Backend returns `text/html` response body → `wrapResponse()` attempts JSON parse, fails, falls back to `NullNode` body → response-direction transforms can still operate on headers and status code → body passthrough unmodified. Outcome: PASSTHROUGH for body transforms, SUCCESS for header-only transforms. |
| S-002-33 | **JMX metrics opt-in:** Admin configures `enableJmxMetrics=true` → adapter registers MBean on `configure()` → JConsole shows `io.messagexform:type=TransformMetrics,instance=<name>` → counters increment per request → admin calls `resetMetrics()` via JConsole → counters zero. Toggle to `false` → MBean unregistered → JConsole no longer shows it. |
| S-002-34 | **JMX metrics disabled (default):** Admin does not toggle `enableJmxMetrics` → no MBean registered → no JMX overhead → SLF4J logging still operational → `pingaccess_engine_audit.log` records transaction timing. |

---

## Test Strategy

### Unit Tests

- **`PingAccessAdapterTest`** — Tests all `wrapRequest`, `wrapResponse`, and
  `applyChanges` methods with mock `Exchange`/`Request`/`Response`/`Body`/`Headers`
  objects. Follows the pattern of `StandaloneAdapterTest`.
  - Mock framework: Mockito (already in use).
  - Key verification: all 9 `Message` fields populated correctly, deep copy
    semantics, header normalization (lowercase), null/empty body handling.

- **`MessageTransformRuleTest`** — Tests the `handleRequest` / `handleResponse`
  lifecycle with a real `TransformEngine` and mock `Exchange`.
  - Key verification: transform applied, changes written back, error modes,
    no-match pass-through, `ExchangeProperty` metadata set.
  - For DENY mode request-phase: verify `ResponseBuilder` → `exchange.setResponse()` path.

- **`MessageTransformConfigTest`** — Tests configuration validation
  (`@UIElement` constraints, required fields, defaults).
  - Uses standalone `Validation.buildDefaultValidatorFactory()` (Jakarta Bean
    Validation). This avoids Spring test context dependency.

- **`SpiRegistrationTest`** — Verifies that the adapter plugin is correctly
  registered via `META-INF/services/`. Uses `ServiceFactory.isValidImplName()`
  (SDK guide §11):

  ```java
  @Test
  void pluginIsRegisteredViaSpi() {
      assertTrue(ServiceFactory.isValidImplName(
          AsyncRuleInterceptor.class,
          "io.messagexform.pingaccess.MessageTransformRule"
      ));
  }

  @Test
  void nonPluginClassIsNotRegistered() {
      assertFalse(ServiceFactory.isValidImplName(
          AsyncRuleInterceptor.class,
          "io.messagexform.pingaccess.PingAccessAdapter"
      ));
  }
  ```

> **Mock patterns, config validation code, and dependency alignment table:**
> See [SDK guide §11](pingaccess-sdk-guide.md#11-testing-patterns)
> for the complete Mockito mock chain, `ArgumentCaptor` verification pattern,
> standalone `Validator` setup, and SDK dependency versions.

> **SDK test infrastructure (SDK guide §12):** When Mockito mocks are
> insufficient (e.g., integration-level tests that need real `Body`,
> `Headers`, or `ResponseBuilder` instances), use `ServiceFactory`:
>
> | Factory | Method | Creates |
> |---------|--------|---------|
> | `ServiceFactory.bodyFactory()` | `createBody(byte[])`, `createEmptyBody()` | Real `Body` instances |
> | `ServiceFactory.headersFactory()` | `createHeaders()`, `createHeaders(List<HeaderField>)` | Real `Headers` instances |
> | `ResponseBuilder` static factories | `newInstance(HttpStatus)`, `ok()`, etc. | Real `Response` instances |
>
> **Note:** Using `ServiceFactory` requires the PA SDK on the test classpath
> (already available as `compileOnly` → add as `testCompileOnly` if needed).
> For most unit tests, Mockito mocks are preferred. `ServiceFactory` is for
> integration tests that exercise real SDK object behavior.


### Integration Tests

- **Docker-based E2E** (FR-002-12) — deferred to post-implementation phase.
  Script-based, runs against real PingAccess container.

---

## Interface & Contract Catalogue

### Build Commands (CLI)

| ID | Command | Behaviour |
|----|---------|-----------|
| CLI-002-01 | `./gradlew :adapter-pingaccess:build` | Compile + test adapter module |
| CLI-002-02 | `./gradlew :adapter-pingaccess:shadowJar` | Build deployable shadow JAR |
| CLI-002-03 | `./gradlew :adapter-pingaccess:test` | Run adapter unit tests only |

### Configuration Files

| ID | Path | Purpose |
|----|------|---------|
| CFG-002-01 | `adapter-pingaccess/build.gradle.kts` | Module build config, PA SDK dependency |
| CFG-002-02 | `settings.gradle.kts` | Include `adapter-pingaccess` subproject |
| CFG-002-03 | `adapter-pingaccess/src/main/resources/META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor` | SPI registration |

### Source Files (planned)

| ID | Path | Purpose |
|----|------|---------|
| SRC-002-01 | `adapter-pingaccess/src/main/java/io/messagexform/pingaccess/PingAccessAdapter.java` | `GatewayAdapter<Exchange>` implementation |
| SRC-002-02 | `adapter-pingaccess/src/main/java/io/messagexform/pingaccess/MessageTransformRule.java` | `AsyncRuleInterceptorBase` plugin |
| SRC-002-03 | `adapter-pingaccess/src/main/java/io/messagexform/pingaccess/MessageTransformConfig.java` | `SimplePluginConfiguration` with `@UIElement` fields |
| SRC-002-04 | `adapter-pingaccess/src/test/java/io/messagexform/pingaccess/PingAccessAdapterTest.java` | Adapter unit tests |
| SRC-002-05 | `adapter-pingaccess/src/test/java/io/messagexform/pingaccess/MessageTransformRuleTest.java` | Plugin lifecycle tests |
| SRC-002-06 | `adapter-pingaccess/src/test/java/io/messagexform/pingaccess/MessageTransformConfigTest.java` | Config validation tests |
| SRC-002-07 | `adapter-pingaccess/src/test/java/io/messagexform/pingaccess/SpiRegistrationTest.java` | SPI registration verification |

---

## Constraints

1. **Site-only deployment:** The `@Rule(destination = Site)` annotation is
   mandatory. Agent rules cannot access request body or invoke `handleResponse`.
2. **Restart required:** PingAccess must be restarted after deploying/updating
   the plugin JAR. There is no hot-reload for Java plugins (Groovy scripts
   support hot-reload but are out of scope). Spec YAML files *can* be
   reloaded without restart if `reloadIntervalSec > 0` (FR-002-04).
3. **Response immutability (PA 6.1+):** Modifying *request* fields during
   `handleResponse()` processing is ignored (logged as warning by PA). The
   adapter MUST NOT attempt to modify request data in the response phase.
4. **Thread safety:** Plugin instances may be shared across threads.
   Framework-injected fields (`@Inject` setters for `ObjectMapper`,
   `TemplateRenderer`, `HttpClient`) and `configuration` are set once during
   initialization and are safe for concurrent read access (effectively immutable
   after `configure()` completes). All per-request state (wrapped messages,
   transform results, error bodies) must be method-local.
5. **Body.read() may be deferred:** The `Body.getContent()` call may trigger
   a lazy read. The adapter MUST call `body.read()` if `!body.isRead()`
   before accessing content. **On failure** (`AccessException` or `IOException`
   from `body.read()`): treat as empty body (`NullNode`), log warning with
   exception details. This is consistent with the "malformed JSON → NullNode"
   fallback in FR-002-01.
6. **No pipeline halt for responses:** `handleResponse()` returns
   `CompletionStage<Void>`, not `Outcome`. The adapter cannot prevent
   response delivery — it can only rewrite the response body/status.
   This affects DENY error mode semantics (FR-002-11).
7. **Body size and memory:** `body.isInMemory()` can be `false` for large
   bodies (streamed from backend). The adapter calls `body.read()` to load
   the body into memory before `getContent()`.

   **Input body limit:** PingAccess enforces a configurable maximum body size
   at the engine level (`body.read()` throws `AccessException` if exceeded).
   The adapter does NOT impose an additional limit beyond PingAccess's built-in
   enforcement. On `AccessException` from `body.read()`, the adapter falls back
   to `NullNode` (per FR-002-01 body read failure strategy).

   **Output body limit:** The core engine's `maxOutputSizeBytes` (NFR-001-07)
   limits the transformed output size.

   **Recommendation:** Administrators SHOULD configure PingAccess's engine-level
   body size limit to prevent excessive memory consumption. The default PA limit
   applies to all rules, not just this adapter.
8. **Identity may be null:** For unauthenticated/anonymous resources,
   `exchange.getIdentity()` returns `null`. The adapter MUST check for null
   before accessing identity fields. See FR-002-06 null-identity handling.
9. **PA-specific status codes:** PingAccess defines two non-standard HTTP
   status codes: `HttpStatus.ALLOWED` (277) and `HttpStatus.REQUEST_BODY_REQUIRED`
   (477). These are PA-internal codes used by the engine — the adapter MUST NOT
   map to or from these codes. If the backend returns a non-standard status code,
   the adapter passes it through unchanged. Transform specs targeting status codes
   should only use standard HTTP status codes (100–599).
10. **No compressed body support (v1):** The adapter does NOT decompress
    `Content-Encoding: gzip/deflate/br` response bodies. If the backend sends
    a compressed body, JSON parsing will fail and the body falls back to
    `NullNode` (headers-only transform). PingAccess administrators SHOULD
    configure sites to disable response compression for endpoints using this
    rule, or place PA's built-in `OutboundContentRewriteInterceptor` upstream
    to decompress before this rule runs. Compressed body support may be added
    in a future version.

---

## Appendix

### A. PingAccess SDK Dependency Coordinates

```kotlin
// Gradle (Kotlin DSL) — in adapter-pingaccess/build.gradle.kts
repositories {
    maven { url = uri("http://maven.pingidentity.com/release/") }  // HTTP per official SDK docs
}

dependencies {
    implementation(project(":core"))

    // PA SDK — provided at runtime by PingAccess
    compileOnly("com.pingidentity.pingaccess:pingaccess-sdk:9.0.1.0")
    compileOnly("jakarta.validation:jakarta.validation-api:3.1.1")
    compileOnly("jakarta.inject:jakarta.inject-api:2.0.1")
}
```

### B. Adapter Bridge Architecture

```
Client                    PingAccess                        Backend
  │                           │                               │
  │  POST /api/users          │                               │
  │  { "name": "ivan" }       │                               │
  │──────────────────────►    │                               │
  │                           │ handleRequest():               │
  │                           │  1. wrapRequest(exchange)      │
  │                           │  2. engine.transform(msg, ctx) │
  │                           │  3. applyChanges(result, exch) │
  │                           │──────────────────────────────► │
  │                           │                               │
  │                           │  ◄─────────────────────────── │
  │                           │ handleResponse():              │
  │                           │  1. wrapResponse(exchange)     │
  │                           │  2. engine.transform(msg, ctx) │
  │                           │  3. applyChanges(result, exch) │
  │  { "id": 1, "name": ... } │                               │
  │  ◄───────────────────── │                               │
```

### C. Related Documents

| Document | Relevance |
|----------|-----------|
| [`deployment-architecture.md`](deployment-architecture.md) | Two-level matching, per-instance config, Docker/K8s layout |
| `docs/research/pingaccess-plugin-api.md` | SDK research (complete) |
| `docs/research/pingaccess-docker-and-sdk.md` | Docker + SDK samples (complete) |
| `docs/research/pingam-authentication-api.md` | PingAM callback format (transform target) |
| `docs/reference/pingaccess-9.0.pdf` | Official PA 9.0 documentation |
| `docs/reference/pingaccess-sdk/pingaccess-sdk-9.0.1.0.jar` | SDK JAR |
| `binaries/pingaccess-9.0.1/sdk/apidocs/` | Official SDK Javadoc |
| `binaries/pingaccess-9.0.1/sdk/samples/` | SDK sample rules |
| `adapter-standalone/src/main/java/.../StandaloneAdapter.java` | Reference adapter implementation |
| ADR-0013 | Copy-on-wrap message adapter pattern |
| ADR-0020 | Nullable status code (requests) |
| ADR-0022 | Error response on transform failure |
| ADR-0024 | Error type catalogue |
| ADR-0027 | URL rewriting |
| ADR-0030 | Session context binding |

### D. Configuration Patterns (SDK Reference)

> Full configuration patterns (annotation-driven, programmatic, `@UIElement`
> attributes, JSR-380 validation) are documented in
> [SDK guide §7](pingaccess-sdk-guide.md#7-plugin-configuration--ui).
>
> **Runtime constraint:** `ConfigurationModelAccessor` instances must only
> be used inside `configure()`, never during `handleRequest()` /
> `handleResponse()` (SDK guide §7). While the adapter does not currently
> use dynamic dropdowns, any future fields using `modelAccessor` must
> respect this constraint.



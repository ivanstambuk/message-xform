# message-xform – Terminology

Status: Draft | Last updated: 2026-02-12

This document defines common terms used across the message-xform docs and specs so we
can use consistent vocabulary. It is the golden source for terminology — any new
terminology agreements must be captured here immediately.

## Core concepts

- **Transform spec** (`TransformSpec`)
  - A self-contained YAML file that defines a single transformation: what input looks
    like, what the output should be, and the expression(s) to get there.
  - Contains: `id`, `version`, `input.schema`, `output.schema`, `transform` block
    (JSLT expression), optional `headers` block, optional `status` block, optional
    `reverse` block.
  - A transform spec is **portable** — it has no knowledge of gateways, URL paths, or
    deployment context. It is a pure function: `input JSON → output JSON`.
  - Multiple versions of the same spec MAY be loaded concurrently (ADR-0005).

- **Transform profile** (`TransformProfile`)
  - A deployment-specific YAML file that binds transform specs to URL patterns.
  - Contains: `profile` id, `transforms` list with `spec` references (using
    `id@version` syntax), `match` criteria (path glob, method, content-type), and
    `direction` (request/response).
  - Profiles are **not portable** — they are specific to a deployment context.
  - When multiple entries **within a single profile** match the same request,
    most-specific-wins resolution applies (ADR-0006). Cross-profile routing is
    determined by the **gateway product's deployment model**, not by the engine.

- **Engine** (core engine)
  - The message-xform execution component: loads transform specs, compiles
    expressions, matches profiles to requests, and evaluates transforms.
  - Preferred wording: "the engine" or "the transform engine". Avoid introducing
    "runtime" as a generic noun.

- **Gateway product**
  - The API gateway software into which message-xform is embedded: PingAccess,
    PingGateway, Kong, Envoy, a standalone reverse proxy, etc.
  - The gateway product owns request routing, TLS termination, authentication, and
    rate limiting. message-xform is a plugin/rule/filter within the gateway product.
  - Preferred wording: "the gateway product" or "the gateway". Avoid "host gateway",
    "underlying platform", or "target gateway".

- **Gateway adapter**
  - The integration layer that bridges the gateway product's native API with the
    message-xform engine. Each gateway product has its own adapter implementation
    (e.g., PingAccess rule adapter, Kong plugin adapter, standalone servlet filter).
  - The adapter wraps native messages into `Message` objects, invokes the engine,
    and applies changes back to native. See ADR-0013 (copy-on-wrap), ADR-0018
    (body buffering).
  - Each adapter gets its own feature spec (N-001-02).

- **Deployment model**
  - How the gateway product is configured to invoke message-xform for incoming
    requests. This is **product-defined** — not controlled by the engine.
  - Examples:
    - PingAccess: adapter rule bound per API operation, context root, or globally.
      Multiple adapter instances may coexist, each with its own profile.
    - Kong: plugin attached to a route or service.
    - Standalone: deployment configuration maps routes to profiles.
  - Cross-profile routing (whether multiple profiles can apply to the same request)
    is a deployment model concern, not an engine concern.

- **Expression engine** (pluggable)
  - A pluggable evaluation component selected via `lang: <engineId>` in a transform
    spec's `transform` block.
  - Implemented via the Expression Engine SPI (FR-001-02). Candidate engines: `jslt`
    (baseline), `jolt`, `jq`, `jsonata`, `dataweave`.
  - Expression engines are pure — they compute values and return results. They do
    not perform external I/O or control flow.
  - Not all engines support the same capabilities. The engine support matrix
    (ADR-0004) documents per-engine capabilities.

- **Engine id**
  - The string identifier used in `lang` to select an expression engine (e.g.,
    `jslt`, `jolt`, `jq`, `jsonata`, `dataweave`).
  - Resolved by the engine's Expression Engine registry; unknown ids MUST be rejected
    at load time.

- **JsonTree**
  - Language-neutral spec notation for a parsed JSON tree. In TypeScript-style
    type definitions (docs-style §6a), `JsonTree` replaces implementation-specific
    names like Jackson's `JsonNode`. The Java implementation maps `JsonTree` to
    `com.fasterxml.jackson.databind.JsonNode`.

## Message envelope

- **Message** (`Message`)
  - The domain object representing a gateway request or response. Contains four
    dimensions: body (JSON), headers (multi-valued map), status code (integer),
    and URL (request path + query parameters + HTTP method).
  - The engine operates on all four dimensions — body via JSLT expressions, headers
    via the declarative `headers` block, status via the declarative `status` block,
    and URL via the declarative `url` block (ADR-0027).

- **Body**
  - The JSON payload of the message. Represented as Jackson `JsonNode` internally.
  - Transform expressions (JSLT) operate on the body.

- **Headers**
  - HTTP headers as a `Map<String, List<String>>`. Manipulated via the declarative
    `headers` block (add/remove/rename) and the read-only `$headers` JSLT variable.
  - `$headers` exposes the **first value** of each header (v1).

- **Status code**
  - The HTTP response status code (integer). Manipulated via the declarative `status`
    block and exposed as the read-only `$status` JSLT variable.

- **URL block** (`url`)
  - The declarative block for rewriting the request URL. Contains three sub-blocks:
    `path` (JSLT expression → new request path), `query` (add/remove query
    parameters), and `method` (override HTTP method with `set`/`when` pattern).
  - URL expressions evaluate against the **original** (pre-transform) body, not
    the transformed body (ADR-0027). This is a documented exception to the convention
    used by `headers.add.expr` and `status.when`.
  - Only meaningful for request-direction transforms.

- **De-polymorphization**
  - The process of converting a polymorphic endpoint (e.g., `POST /dispatch` where
    the operation is determined by a body field like `action`) into specific
    REST-style URLs (e.g., `DELETE /api/users/123`). The primary use case for URL
    rewriting (FR-001-12, ADR-0027).

- **Original-body evaluation context**
  - URL expressions evaluate against the body as received from the gateway, before
    the body transform has run. This ensures routing fields (e.g., `action`,
    `resourceId`) are available for URL construction even though the body transform
    may strip them. Contrast with headers/status, which use the **transformed** body.
    Rationale: "route the input, enrich the output" (ADR-0027).

## Versioning & resolution

- **Spec version**
  - Semver string on a transform spec (e.g., `1.0.0`). Profiles reference specs
    by `id@version` for explicit pinning (ADR-0005).

- **Version pinning**
  - The practice of referencing a specific spec version in a profile using `id@version`
    syntax. Enables concurrent versions and gradual migration.

- **Latest resolution**
  - When a profile references a spec **without** `@version`, the engine resolves to
    the latest loaded version (highest semver). Production profiles SHOULD always use
    explicit version pinning.

- **Specificity score**
  - The count of literal (non-wildcard) path segments in a profile entry's match
    pattern. Used for most-specific-wins resolution when multiple entries **within
    a single profile** match the same request (ADR-0006). Higher score = more
    specific = wins.

- **Version parity** (ADR-0035)
  - The convention that a gateway adapter module's version mirrors the target
    gateway version exactly: `<GATEWAY_MAJOR>.<GATEWAY_MINOR>.<GATEWAY_PATCH>.<ADAPTER_PATCH>`.
    The first 3 segments signal compatibility; the 4th is the adapter's own
    patch counter. Applies to all gateway adapters (PingAccess, future Kong/Envoy).

- **Misdeployment guard** (ADR-0035)
  - A runtime safety check that compares the adapter's compiled-against gateway
    version with the detected runtime gateway version.
  - On mismatch, it logs a WARN with remediation to deploy the matching adapter
    version line. It does not fail fast or abort plugin activation.

## Transform lifecycle

- **Load time** (spec load / profile load)
  - When specs and profiles are loaded from YAML into compiled engine objects.
    All validation occurs here: schema syntax, engine capability checks, version
    resolution, ambiguous match detection.
  - Prefer "load time" over "deploy time" or "startup time".

- **Evaluation time**
  - When a request arrives and the engine evaluates the matched transform spec
    against the message body.
  - Evaluation-time schema validation (strict/lenient mode) occurs here.

- **Passthrough**
  - When a message does not match any transform profile, the engine passes it
    through **completely unmodified** — no body, header, or status code changes.
  - Passthrough applies **only** to non-matching requests. When a profile matches
    but the transformation fails, the engine returns an error response instead
    (ADR-0022).

- **Error mode** (`ErrorMode`, FR-002-11)
  - A per-rule configuration that controls behavior when the transform engine
    returns an ERROR result. Two modes: PASS_THROUGH and DENY.
  - Configured in `MessageTransformConfig.errorMode`.

- **PASS_THROUGH** (error mode)
  - Error mode that logs a warning and preserves the original message/response
    unmodified. The request continues to the backend (or the original response
    returns to the client). Scenario: S-002-11.

- **DENY** (error mode)
  - Error mode that rejects the request or rewrites the response with an
    RFC 9457 error body. In request phase: builds a new `Response` via
    `ResponseBuilder` and returns `Outcome.RETURN`. In response phase:
    rewrites the existing response in-place. Sets `TRANSFORM_DENIED` exchange
    property to guard the response phase. Scenarios: S-002-12, S-002-28.

- **bodyParseFailed** (skip-guard, S-002-08)
  - A flag set by the adapter when the request or response body cannot be
    parsed as JSON. When true, body JSLT expressions are NOT evaluated and
    the original raw bytes are preserved. Header, URL, and status transforms
    still apply. This prevents transform failures when the body is not JSON
    (e.g. binary, plain text, or malformed).

## Pipeline chaining & message semantics

- **TransformContext** (`TransformContext`, DO-001-07)
  - A read-only context record passed to expression engines during evaluation.
    Fields: `headers` (HttpHeaders — engines derive `$headers`/`$headers_all`),
    `status` (Integer, null for requests per ADR-0020), `queryParams`
    (Map\<String,String\>, ADR-0021), `cookies` (Map\<String,String\>, ADR-0021),
    `session` (SessionContext, ADR-0030). All nullable fields are null-normalized
    by the canonical constructor. Engines consume whichever context they support.

- **Session context** (`$session`, ADR-0030)
  - An arbitrary JSON structure provided by the gateway's session management layer,
    representing the state of a user or workload session. Not part of the HTTP message
    itself — it's gateway-level metadata. Exposed as a read-only `$session` variable
    in JSLT expressions. Nullable: when the gateway does not support sessions or no
    session exists, `$session` is `null`. JSLT handles null gracefully.
    Adapters are responsible for populating `Message.session()` from their
    gateway-native session API. See ADR-0030.

- **Pipeline chaining** (profile-level chaining)
  - When multiple transform entries in a single profile match the same request,
    they execute as an ordered pipeline: output of spec N feeds spec N+1.
    `TransformContext` is re-read between steps. See ADR-0012.

- **Abort-on-failure**
  - If any step in a pipeline chain fails, the **entire chain aborts**. The engine
    returns a **configurable error response** to the caller (ADR-0022). No partial
    pipeline results reach the client or downstream service. The original message is
    NOT passed through — the downstream expects the transformed schema and would fail.

- **Copy-on-wrap**
  - Adapter strategy where the gateway-native message is deep-copied when wrapped
    into a `Message`. The engine mutates the copy; on success, `applyChanges()`
    writes back to native; on failure, the copy is discarded and the adapter
    returns an error response to the caller (ADR-0022). See ADR-0013.

- **Atomic registry swap**
  - The core engine's ability to replace the full set of compiled specs and profiles
    via `TransformEngine.reload()` using an immutable `TransformRegistry` snapshot and
    `AtomicReference`. In-flight requests complete with their current registry; new
    requests pick up the new one. Reload *trigger* mechanisms (file watching, polling)
    are adapter concerns.

- **TransformRegistry** (`TransformRegistry`, DO-001-10)
  - An immutable snapshot holding all loaded `TransformSpec` instances (keyed by both
    `id` and `id@version`) and an optional active `TransformProfile`. Created via
    `TransformRegistry.builder()` or `TransformRegistry.empty()`. The unit of atomic
    swap — `TransformEngine` holds an `AtomicReference<TransformRegistry>` and replaces
    it wholesale on `reload()`. Defensive copy on construction guarantees thread safety.

## Bidirectional transforms

- **Forward transform**
  - The primary transform direction: `input → output` using the `transform` block.

- **Reverse transform**
  - The inverse direction: `output → input` using the `reverse` block. Enables
    bidirectional specs (FR-001-03) where the same spec handles both request and
    response transformation.

- **Direction**
  - `request` or `response` — indicates whether the transform applies to the
    incoming request or the outgoing response. Specified in the profile.

- **Direction-agnostic** (spec property)
  - A unidirectional spec (only `transform`, no `forward`/`reverse`) that is
    bound to a direction by the profile rather than declaring one itself. The same
    direction-agnostic spec MAY be bound to both directions in different profile
    entries. See ADR-0016.

- **Prerequisite filter** (spec `match`)
  - The spec-level `match` block declares what the spec can process (a capability
    declaration), not where it is routed. Distinct from the profile-level `match`
    block which handles routing. See ADR-0015.

- **Mapper** (spec `mappers` block entry)
  - A named, reusable expression defined in the `mappers` block of a transform spec.
    Each mapper has an `expr` (and optional `lang`) and is referenced by name via
    `mapperRef` in the `apply` directive. Mappers are compiled at load time and
    executed as pipeline steps — they are NOT callable as inline JSLT functions.
    See FR-001-08, ADR-0014.

- **Apply directive** (`apply`)
  - An ordered list of steps in the `transform` block that sequences named mappers
    with the main `expr`. Steps are either `mapperRef: <name>` or `expr` (the main
    expression). `expr` must appear exactly once. See ADR-0014.

- **Sensitive list** (`sensitive`)
  - A top-level optional block in the spec YAML listing JSON path expressions
    (RFC 9535) for fields that MUST be redacted from logs, caches, and telemetry.
    See ADR-0019, NFR-001-06.

## Observability

- **TelemetryListener** (SPI interface)
  - A plain Java interface in the core module that receives semantic transform
    events (started, completed, failed, matched, loaded, rejected). Adapter
    modules provide concrete OTel/Micrometer bindings. See ADR-0007.

- **Structured log entry**
  - A JSON-formatted log line emitted by the engine for operational events. Every
    matched profile produces a structured log entry (NFR-001-08) containing:
    profile id, spec id@version, request path, specificity score, evaluation duration.

- **Trace context**
  - Incoming correlation headers (`X-Request-ID`, `traceparent`) propagated through
    all log entries and telemetry events (NFR-001-10). The engine participates in the
    caller's trace — it does not create new traces.

## Standalone proxy (Feature 004)

- **Standalone proxy** (`adapter-standalone`)
  - The message-xform engine running as an independent HTTP reverse proxy without
    any gateway product. Receives HTTP requests, applies transform profiles via the
    `TransformEngine`, forwards to a configured backend, transforms the response,
    and returns to the client. Deployed as a shadow JAR or Docker image.
  - Implements `GatewayAdapter<Context>` using Javalin 6 (Jetty 12) with Java 21
    virtual threads (ADR-0029).
  - Serves as the **reference adapter** — the first concrete `GatewayAdapter`
    implementation and the pattern for all subsequent gateway adapters.

- **Standalone adapter** (`StandaloneAdapter`)
  - The `GatewayAdapter<Context>` implementation for the standalone proxy. Wraps
    Javalin's `Context` into `Message` objects (`wrapRequest`, `wrapResponse`) and
    writes transformed results back via `applyChanges`. Parses cookies and query
    parameters into `TransformContext` for `$cookies` / `$queryParams` binding.

- **Proxy handler** (`ProxyHandler`)
  - The central HTTP handler that orchestrates the full proxy cycle: receive request
    → wrap → request-transform → forward to backend → wrap response → response-
    transform → return to client. Implements the `TransformResult` dispatch table
    (FR-004-35) and generates/echoes `X-Request-ID` headers (FR-004-38).

- **Proxy config** (`ProxyConfig`, `BackendTlsConfig`, `TlsConfig`, `PoolConfig`)
  - Immutable record hierarchy defining all proxy configuration. Loaded from YAML
    (`message-xform-proxy.yaml`) via `ConfigLoader` with environment variable
    overlay (FR-004-11). `ProxyConfig` is the root (with flat backend fields and
    a builder); nested records hold inbound TLS, outbound TLS, and connection pool
    settings. 41 config keys (CFG-004-01 through CFG-004-41) with corresponding
    environment variable mappings.

- **File watcher** (`FileWatcher`)
  - A `WatchService`-based component that monitors the specs directory for changes
    and triggers `TransformEngine.reload()` with configurable debounce. Provides
    zero-downtime hot reload (NFR-004-05). Can be disabled via `reload.enabled: false`.

- **Admin reload handler** (`AdminReloadHandler`)
  - Javalin handler for `POST /admin/reload` (FR-004-20). Scans the specs directory
    for `*.yaml`/`*.yml` files, calls `TransformEngine.reload()`, and returns a JSON
    summary (`{"status": "reloaded", "specs": N, "profile": "id-or-none"}`). On failure,
    returns `500` with RFC 9457 problem detail; the previous registry stays active.
    Registered as a dedicated route before the wildcard proxy handler — not subject to
    profile matching.

- **Sidecar pattern**
  - A Kubernetes deployment model where the standalone proxy runs as a container
    alongside the backend application in the same pod. Traffic flows through the
    proxy for transformation before reaching the backend. The proxy uses
    `localhost` as the backend host.

- **Upstream client** (`UpstreamClient`)
  - The component that forwards (potentially transformed) HTTP requests to the
    configured backend. Uses JDK `HttpClient` with HTTP/1.1. Recalculates
    `Content-Length` after body transformation.

- **Hop-by-hop headers**
  - HTTP headers that are meaningful only for a single transport-level connection
    and must not be forwarded by proxies (RFC 9110 §7.6.1). Includes
    `Connection`, `Keep-Alive`, `Transfer-Encoding`, `TE`, `Trailer`,
    `Proxy-Authorization`, `Proxy-Authenticate`, and `Upgrade`. In this project,
    `UpstreamClient` strips hop-by-hop headers from forwarded requests, and
    `ProxyHandler` filters `Content-Length` and `Transfer-Encoding` from upstream
    responses to prevent body truncation when response transforms change the body
    size.

- **Problem detail** (`ProblemDetail`, RFC 9457)
  - A standardised JSON error response format (`application/problem+json`) used
    for all proxy-level errors: backend failures (502/504), body size violations
    (413), non-JSON body on matched route (400), and method rejection (405).
    Transform errors use the core `ErrorResponseBuilder` (ADR-0022) which produces
    the same RFC 9457 structure. Every problem detail includes `type` (URN),
    `title`, `status`, `detail`, and `instance` (request path) fields.

- **Upstream exception** (`UpstreamException` hierarchy)
  - Domain-specific exception classes for backend communication failures.
    `UpstreamConnectException` covers connection refused and host unreachable
    (→ 502 Bad Gateway). `UpstreamTimeoutException` covers read timeouts
    (→ 504 Gateway Timeout). `ProxyHandler` catches these and converts them
    to RFC 9457 problem details via `ProblemDetail`.

- **TLS configurator** (`TlsConfigurator`)
  - Configures Javalin's embedded Jetty for inbound TLS (HTTPS) serving
    (FR-004-14, FR-004-15). Uses Jetty 11's `SslContextFactory.Server` to
    terminate TLS, supporting server keystore, client-auth modes (`none`,
    `want`, `need`), and truststore for mTLS client certificate validation.
    Added via `config.jetty.addConnector()`.

- **TLS config validator** (`TlsConfigValidator`)
  - Startup-time validation of TLS settings (S-004-43). Checks keystore and
    truststore file existence, readability, correct passwords, and mTLS
    requirements (e.g., `client-auth=need` requires a truststore). Called
    before the server starts to provide descriptive errors instead of cryptic
    runtime failures.

- **Mutual TLS (mTLS)**
  - Two-way TLS authentication. **Inbound mTLS** (FR-004-15): the proxy
    requires connecting clients to present a certificate verified against a
    configured truststore. **Outbound mTLS** (FR-004-17): the proxy presents
    a client certificate from a configured keystore when connecting to the
    backend. Both rely on `SSLContext` with `KeyManagerFactory` and/or
    `TrustManagerFactory`.

- **Shadow JAR**
  - A single fat JAR containing the `core`, `adapter-standalone`, and all
    transitive dependencies. Produced by the Gradle Shadow plugin. The Docker
    image runs `java -jar proxy.jar` with no classpath setup required.

## Toolchain & quality (Feature 009)

- **Quality gate**
  - The automated verification pipeline that must pass before code is committed or
    merged. In message-xform this means `./gradlew --no-daemon spotlessCheck check`
    — which runs formatting verification, compilation with `-Werror`, and all JUnit 5
    tests. Enforced locally by `githooks/pre-commit` and remotely by
    `.github/workflows/ci.yml`. See `docs/operations/quality-gate.md` and Feature 009.

- **Version catalog** (`gradle/libs.versions.toml`)
  - Gradle's centralized dependency version management. All dependency and plugin
    versions are pinned here — never in individual `build.gradle.kts` files.
    Ensures version consistency across modules and makes upgrades auditable.

## Wording conventions

- Use **"transform spec"** for the transformation definition YAML.
- Use **"transform profile"** for the deployment binding YAML.
- Use **"the engine"** for the core execution component.
- Use **"expression engine"** for the pluggable evaluation component (JSLT, JOLT, etc.).
- Use **"gateway product"** for the API gateway software (PingAccess, Kong, etc.).
  The shorter form **"the gateway"** is acceptable in context.
- Use **"gateway adapter"** for the integration layer between a gateway product and
  the engine. Each adapter is product-specific.
- Use **"deployment model"** for how the gateway product invokes message-xform.
  Cross-profile routing is a deployment model concern.
- Use **"product-defined"** when a behaviour depends on the gateway product's
  configuration, not the engine's. Avoid "implementation-defined" or
  "adapter-defined" for cross-profile concerns.
- Use **"load time"** for spec/profile loading and validation.
- Use **"evaluation time"** for per-request transform execution (when the engine
  evaluates a compiled expression against a message).
- Do **not** introduce "runtime" as a generic noun in new prose. Legacy
  identifiers or external system names containing "runtime" may remain unchanged.

### Canonical term map (use vs avoid)

| Use | Avoid |
|-----|-------|
| transform spec | mapping, transformation rule, transform definition |
| transform profile | route config, binding config, deployment spec |
| the engine | the runtime, the transformer, the processor |
| expression engine | language plugin, eval plugin, script engine |
| gateway product | host gateway, underlying platform, target gateway, gateway vendor |
| gateway adapter | bridge, connector, integration layer |
| deployment model | routing config, rule binding |
| load time | deploy time, startup time, init time |
| evaluation time | runtime, execution time (when referring to transform execution) |
| body | payload, message body |
| `$headers` | the first-value header context variable |
| `$headers_all` | the multi-value header context variable (arrays) — ADR-0026 |
| passthrough | bypass, skip, no-op |
| specificity score | priority number, weight, rank |
| product-defined | implementation-defined, adapter-defined (for cross-profile routing) |

### Process for improving terminology

- When a better term or clearer definition is identified, propose it via
  `docs/architecture/open-questions.md` or an ADR and update this document.
- Before merging new docs/specs, search (`rg`) for avoided terms to ensure they
  do not appear in normative text.
- This document is the golden source. Subsequent docs must follow it without exception.

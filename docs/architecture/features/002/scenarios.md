# Feature 002 – PingAccess Adapter: Scenarios

| Field | Value |
|-------|-------|
| Status | Draft |
| Last updated | 2026-02-13 |
| Linked spec | `docs/architecture/features/002/spec.md` |
| Format | Behavioral Contract (see `docs/architecture/spec-guidelines/scenarios-format.md`) |

> Guardrail: Each scenario is a **testable contract** expressed as structured
> YAML. Scenarios serve as integration test fixtures — they can be loaded or
> parsed directly by test infrastructure.

---

## S-002-01: Request Body Transform

```yaml
scenario: S-002-01
name: request-body-transform
description: >
  POST request body is transformed by the matched spec before forwarding
  to the backend. Content-Length is auto-updated.
tags: [body, request, core]
refs: [FR-002-01, FR-002-03]

setup:
  exchange:
    request:
      method: POST
      uri: /api/users
      contentType: application/json
      body: '{"name": "ivan"}'
  specs:
    - id: user-rewrite
      matches: "POST /api/users"
      direction: request
      expr: '{"username": .name, "source": "pa"}'

trigger: handleRequest

assertions:
  - description: backend receives transformed body
    expect: response.body == '{"username": "ivan", "source": "pa"}'
  - description: Content-Length is auto-updated to match new body size
    expect: response.header["Content-Length"] == new body byte length
  - description: rule returns CONTINUE
    expect: outcome == CONTINUE
```

---

## S-002-02: Response Body Transform

```yaml
scenario: S-002-02
name: response-body-transform
description: >
  Response body is transformed by the matched spec before returning to
  the client. Internal fields are stripped.
tags: [body, response, core]
refs: [FR-002-02, FR-002-03]

setup:
  exchange:
    request:
      method: GET
      uri: /api/status
    response:
      statusCode: 200
      contentType: application/json
      body: '{"result": "ok", "debug_token": "abc123"}'
  specs:
    - id: strip-debug
      matches: "GET /api/status"
      direction: response
      expr: '{"result": .result}'

trigger: handleResponse

assertions:
  - description: client receives body with debug_token stripped
    expect: response.body == '{"result": "ok"}'
  - description: Content-Length is updated
    expect: response.header["Content-Length"] == new body byte length
```

---

## S-002-03: Bidirectional Transform

```yaml
scenario: S-002-03
name: bidirectional-transform
description: >
  A spec with both request and response directions transforms the request
  body on the way in and the response body on the way out. No shared
  state between phases.
tags: [body, bidirectional, core]
refs: [FR-002-03]

setup:
  exchange:
    request:
      method: POST
      uri: /api/auth
      contentType: application/json
      body: '{"user": "ivan"}'
    response:
      statusCode: 200
      contentType: application/json
      body: '{"token": "abc", "internal": true}'
  specs:
    - id: auth-transform
      matches: "POST /api/auth"
      direction: both
      forward_expr: '{"username": .user}'
      reverse_expr: '{"token": .token}'

trigger: handleRequest + handleResponse

assertions:
  - description: handleRequest transforms request body before forwarding
    expect: forwarded.body == '{"username": "ivan"}'
  - description: handleResponse transforms response body before returning
    expect: returned.body == '{"token": "abc"}'
  - description: no shared mutable state between request and response phases
    expect: phases are independent
```

---

## S-002-04: Header Transform

```yaml
scenario: S-002-04
name: header-transform
description: >
  A spec adds and removes headers on the request. Untargeted headers
  are preserved unchanged.
tags: [headers, request, core]
refs: [FR-002-04, FR-001-10]

setup:
  exchange:
    request:
      method: GET
      uri: /api/data
      headers:
        X-Internal-Debug: "verbose"
        Accept: "application/json"
  specs:
    - id: header-ops
      matches: "GET /api/data"
      direction: request
      headers:
        add:
          X-Transformed: "true"
        remove:
          - X-Internal-Debug

trigger: handleRequest

assertions:
  - description: X-Transformed header is added
    expect: forwarded.header["X-Transformed"] == "true"
  - description: X-Internal-Debug header is removed
    expect: forwarded.header["X-Internal-Debug"] == absent
  - description: untargeted headers are preserved
    expect: forwarded.header["Accept"] == "application/json"
```

---

## S-002-05: Status Code Transform

```yaml
scenario: S-002-05
name: status-code-transform
description: >
  A spec matches a 404 response and maps it to 200 with a synthetic
  fallback body.
tags: [status, response, core]
refs: [FR-002-05, FR-001-11, ADR-0003]

setup:
  exchange:
    request:
      method: GET
      uri: /api/resource
    response:
      statusCode: 404
      body: ""
  specs:
    - id: status-mapper
      matches: "GET /api/resource"
      direction: response
      status:
        when: "$status == 404"
        set: 200
      expr: '{"found": false, "fallback": true}'

trigger: handleResponse

assertions:
  - description: response status is changed from 404 to 200
    expect: response.statusCode == 200
  - description: body is replaced with synthetic JSON
    expect: response.body == '{"found": false, "fallback": true}'
```

---

## S-002-06: URL Rewrite

```yaml
scenario: S-002-06
name: url-rewrite
description: >
  A spec rewrites the request path. Original query string is preserved.
tags: [url, request, core]
refs: [FR-002-12, FR-001-12, ADR-0027]

setup:
  exchange:
    request:
      method: GET
      uri: /legacy/users?page=2
  specs:
    - id: url-rewriter
      matches: "GET /legacy/**"
      direction: request
      url:
        path_expr: '"/api/v2/users"'

trigger: handleRequest

assertions:
  - description: request URI path is rewritten
    expect: forwarded.uri.path == "/api/v2/users"
  - description: original query string is preserved
    expect: forwarded.uri.query == "page=2"
```

## S-002-07: Empty Body

```yaml
scenario: S-002-07
name: empty-body
description: >
  A GET request with no body produces MessageBody.empty(). If no spec
  matches a bodyless request, the exchange passes through unmodified.
tags: [body, edge-case, passthrough]
refs: [FR-002-01, ADR-0013]

setup:
  exchange:
    request:
      method: GET
      uri: /api/health
      body: null
  specs: []

trigger: handleRequest

assertions:
  - description: message body is MessageBody.empty()
    expect: message.body() == MessageBody.empty()
  - description: exchange passes through unmodified (no matching spec)
    expect: outcome == CONTINUE
  - description: no transformation applied
    expect: forwarded.body == null
```

---

## S-002-08: Non-JSON Body

```yaml
scenario: S-002-08
name: non-json-body-passthrough
description: >
  Non-JSON request body triggers parse-failure guard. Body transforms
  are skipped but header/status/URL transforms still apply. Original
  bytes forwarded unchanged (Q-003, Option A).
tags: [body, parse-failure, passthrough, Q-003]
refs: [FR-002-01, ADR-0013]

setup:
  exchange:
    request:
      method: POST
      uri: /api/data
      contentType: text/plain
      body: "hello world"
  specs:
    - id: data-transform
      matches: "POST /api/data"
      direction: request
      expr: '{"wrapped": .}'
      headers:
        add:
          X-Transformed: "true"

trigger: handleRequest

assertions:
  - description: JSON parse fails gracefully
    expect: message.body() == MessageBody.empty()
  - description: bodyParseFailed flag is set
    expect: bodyParseFailed == true
  - description: warning is logged about parse failure
    expect: log.contains("WARN", "JSON parse failed")
  - description: header transforms still apply
    expect: forwarded.header["X-Transformed"] == "true"
  - description: body transforms are skipped (adapter skip guard)
    expect: forwarded.body.content == "hello world"
  - description: original content type is preserved
    expect: forwarded.body.mediaType == "text/plain"
```

---

## S-002-09: Profile Matching

```yaml
scenario: S-002-09
name: profile-matching
description: >
  An active profile filters which specs apply. Requests matching the
  profile are transformed; non-matching requests pass through.
tags: [profile, matching, core]
refs: [FR-002-08, FR-001-06]

setup:
  config:
    activeProfile: api-v1
  exchange:
    request:
      method: POST
      uri: /api/v1/users
      contentType: application/json
      body: '{"name": "ivan"}'
  specs:
    - id: v1-transform
      profile: api-v1
      matches: "POST /api/v1/**"
      direction: request
      expr: '{"username": .name}'

trigger: handleRequest

assertions:
  - description: matching profile + path → spec is applied
    expect: forwarded.body == '{"username": "ivan"}'
```

```yaml
# Negative case (same scenario, different exchange)
scenario: S-002-09b
name: profile-matching-no-match
description: >
  Request path outside the profile filter passes through unmodified.
tags: [profile, matching, passthrough]
refs: [FR-002-08, FR-001-06]

setup:
  config:
    activeProfile: api-v1
  exchange:
    request:
      method: POST
      uri: /admin/settings
      contentType: application/json
      body: '{"theme": "dark"}'
  specs:
    - id: v1-transform
      profile: api-v1
      matches: "POST /api/v1/**"
      direction: request
      expr: '{"username": .name}'

trigger: handleRequest

assertions:
  - description: profile does not match → no transform applied
    expect: forwarded.body == '{"theme": "dark"}'
  - description: exchange passes through unchanged
    expect: outcome == CONTINUE
```

---

## S-002-10: No Matching Spec

```yaml
scenario: S-002-10
name: no-matching-spec
description: >
  When no spec matches the request, the exchange passes through with
  no transformation and no error logged (normal case).
tags: [matching, passthrough, normal]
refs: [FR-002-08]

setup:
  exchange:
    request:
      method: GET
      uri: /favicon.ico
  specs:
    - id: api-transform
      matches: "POST /api/**"
      direction: request
      expr: '{"wrapped": .}'

trigger: handleRequest

assertions:
  - description: no transformation applied
    expect: forwarded == original exchange (unmodified)
  - description: outcome is CONTINUE
    expect: outcome == CONTINUE
  - description: no warning or error logged
    expect: log.absent("WARN") and log.absent("ERROR")
```

## S-002-11: Error Mode PASS_THROUGH

**Given** `errorMode = PASS_THROUGH` in the plugin configuration,
**and** a spec's JSLT expression throws a runtime error (e.g., NPE in expression),
**when** `handleRequest()` processes the exchange,
**then** a warning is logged with the error details,
**and** the original request body is forwarded unchanged (ADR-0013 copy-on-wrap safety),
**and** `Outcome.CONTINUE` is returned.

---

## S-002-12: Error Mode DENY

**Given** `errorMode = DENY` in the plugin configuration,
**and** a spec's JSLT expression throws a runtime error,
**when** `handleRequest()` processes the exchange,
**then** a warning/error is logged,
**and** `Outcome.RETURN` is returned (halts the PingAccess pipeline),
**and** the response body is an RFC 9457 Problem Detail JSON object with
`status: 502`.

---

## S-002-13: Session Context in JSLT

**Given** the PingAccess Exchange has an Identity with `subject = "bjensen"`,
`trackingId = "tx-001"`,
**and** a transform spec uses JSLT expression `.subject = $session.subject`,
**when** `handleRequest()` processes the exchange,
**then** `Message.session()` contains `{"subject": "bjensen", "trackingId": "tx-001", ...}`,
**and** the JSLT expression resolves `$session.subject` to `"bjensen"`.

---

## S-002-14: No Identity (Unauthenticated)

**Given** the PingAccess Exchange has no Identity (unauthenticated request),
**when** `wrapRequest()` is called,
**then** `Message.session()` is `SessionContext.empty()`,
**and** JSLT expressions referencing `$session` see an empty object `{}`,
**and** field access like `$session.subject` evaluates to `null` (key-absent
semantics) without error.

---

## S-002-15: Multiple Specs Loaded

**Given** two specs are loaded:
- Spec A matches `POST /api/users` (transforms user creation body)
- Spec B matches `GET /api/status` (transforms status response)

**when** a `POST /api/users` request arrives,
**then** Spec A is applied, Spec B is not.

**when** a `GET /api/status` request arrives,
**then** Spec B is applied, Spec A is not.

---

## S-002-16: Large Body (64 KB)

**Given** a request with a 64 KB JSON body,
**when** `handleRequest()` transforms it,
**then** the transform completes within the eval budget (default 5000 ms),
**and** no `OutOfMemoryError` occurs,
**and** adapter overhead (excluding engine time) is < 10 ms.

---

## S-002-17: Plugin Configuration via Admin UI

**Given** an admin creates a "Message Transform" rule in the PingAccess admin
console (port 9000),
**and** fills in `specsDir = /opt/specs`, `errorMode = PASS_THROUGH`,
**when** the rule is saved,
**then** PingAccess calls `configure(MessageTransformConfig)` with the JSON config,
**and** the engine initializes and loads specs from `/opt/specs`.

---

## S-002-18: Invalid Spec Directory

**Given** an admin configures `specsDir = /nonexistent/path`,
**when** `configure()` is called,
**then** a `ValidationException` is thrown with a message indicating the directory
doesn't exist,
**and** the rule is NOT activated (PingAccess shows error in admin UI).

---

## S-002-19: Plugin SPI Registration

**Given** the shadow JAR contains
`META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor`
with content `io.messagexform.pingaccess.MessageTransformRule`,
**when** the JAR is deployed to `/opt/server/deploy/` and PingAccess is restarted,
**then** the "Message Transform" rule type appears in the PingAccess admin
console's rule type list.

---

## S-002-20: Thread Safety

**Given** the same `MessageTransformRule` instance handles 10 concurrent requests,
**when** all requests are processed simultaneously,
**then** no data corruption occurs,
**and** each request gets independent transformation results,
**and** no shared mutable state is accessed across threads.

---

## S-002-21: ExchangeProperty Metadata

**Given** a transform is applied successfully (spec matched, body transformed),
**when** `handleRequest()` completes,
**then** `exchange.getProperty(TRANSFORM_RESULT)` returns a `TransformResultSummary`
record containing:
- `specId` — the matched spec identifier
- `specVersion` — the matched spec version
- `direction` — `"REQUEST"` or `"RESPONSE"`
- `durationMs` — transform execution time in milliseconds
- `outcome` — `"SUCCESS"`, `"PASSTHROUGH"`, or `"ERROR"`
- `errorType` — error type code (null if no error)
- `errorMessage` — human-readable error message (null if no error)

---

## S-002-22: Cookie Access in JSLT

**Given** a request contains cookie `session_token=abc123`,
**and** a transform spec uses `$cookies.session_token`,
**when** `handleRequest()` processes the exchange,
**then** the JSLT expression resolves to `"abc123"`.

> **Note:** The JSLT engine binds cookies as a top-level `$cookies` variable,
> not nested under `$context`. The `TransformContext.cookies()` accessor provides
> a first-value `Map<String, String>` — JSON conversion is handled internally
> by the engine.

---

## S-002-23: Query Param Access in JSLT

**Given** a request to `/api/users?page=2&limit=10`,
**and** a transform spec uses `$queryParams.page`,
**when** `handleRequest()` processes the exchange,
**then** the JSLT expression resolves to `"2"`.

> **Note:** The JSLT engine binds query params as a top-level `$queryParams`
> variable, not nested under `$context`. The `TransformContext.queryParams()`
> accessor provides a first-value `Map<String, String>` — JSON conversion is
> handled internally by the engine.

---

## S-002-24: Shadow JAR Correctness

**Given** the shadow JAR is built via `./gradlew :adapter-pingaccess:shadowJar`,
**when** deployed to a PingAccess 9.0 instance,
**then** no `ClassNotFoundException` or `NoClassDefFoundError` occurs at runtime,
**and** the shadow JAR does NOT contain PA SDK classes
(`com.pingidentity.pa.sdk.*`),
**and** the shadow JAR DOES contain all core + transitive dependencies (JSLT,
SnakeYAML, JSON Schema Validator, and core's relocated Jackson at
`io.messagexform.internal.jackson`),
**and** the shadow JAR does NOT contain `com/fasterxml/jackson/` (PA-provided,
must not be bundled per FR-002-09).

---

## S-002-25: OAuth Context in JSLT

**Given** the PingAccess Exchange has an Identity with
`OAuthTokenMetadata.getClientId() = "my-app"` and
`OAuthTokenMetadata.getScopes() = ["openid", "email"]`,
**and** a transform spec uses `$session.clientId` and `$session.scopes`,
**when** `handleRequest()` processes the exchange,
**then** `$session.clientId` resolves to `"my-app"`,
**and** `$session.scopes` resolves to `["openid", "email"]`.

> **Note:** The `$session` namespace is flat — OAuth metadata is merged at
> layer 2 (see FR-002-06). There is no `$session.oauth` nesting.

---

## S-002-26: Session State in JSLT

**Given** a prior rule has stored `authzCache = "PERMIT"` via
`SessionStateSupport` on the Exchange,
**and** a transform spec uses `$session.authzCache`,
**when** `handleRequest()` processes the exchange,
**then** `$session.authzCache` resolves to `"PERMIT"`.

> **Note:** Session state attributes are merged flat into `$session` at layer 4
> (highest precedence). There is no `$session.sessionState` nesting.

---

## S-002-27: Prior Rule URI Rewrite

**Given** an upstream `ParameterRule` rewrites the request URI from `/old/path`
to `/new/path` before the `MessageTransformRule` executes,
**when** `wrapRequest()` reads the request URI via `exchange.getRequest().getUri()`,
**then** the adapter wraps the **rewritten** URI (`/new/path`),
**and** `Message.requestPath()` contains `/new/path`,
**and** spec matching uses the rewritten URI, not the original client URI.

> **Note:** This validates the FR-002-01 note that `Request.getUri()` reflects
> modifications by prior rules in the interceptor chain.

---

## S-002-28: DENY + handleResponse Interaction

**Given** `errorMode = DENY` in the plugin configuration,
**and** a spec's JSLT expression throws a runtime error during `handleRequest()`,
**when** `handleRequest()` returns `Outcome.RETURN` with an RFC 9457 error body,
**then** PingAccess still calls `handleResponse()` (SDK contract — response
interceptors fire in reverse order even on `RETURN`),
**and** `handleResponse()` checks `exchange.getProperty(TRANSFORM_DENIED)`,
**and** finding it `true`, skips all response processing,
**and** the client receives the original DENY error response unchanged.

> **Note:** Without the DENY guard (GAP-4 fix), `handleResponse()` would
> overwrite the error response with a normal response transform.

---

## S-002-29: Spec Hot-Reload (Success)

**Given** `reloadIntervalSec = 30` in the plugin configuration,
**and** the adapter has loaded specs from `specsDir` during `configure()`,
**when** an operator modifies a spec YAML file on disk,
**and** the next scheduled reload poll fires,
**then** `TransformEngine.reload()` is called,
**and** the updated spec is loaded into the `TransformRegistry`,
**and** subsequent requests use the updated spec.

---

## S-002-30: Spec Hot-Reload (Failure)

**Given** `reloadIntervalSec = 30` in the plugin configuration,
**and** the adapter has loaded valid specs during `configure()`,
**when** an operator writes a malformed YAML file to `specsDir`,
**and** the next scheduled reload poll fires,
**then** `TransformEngine.reload()` fails with a warning log,
**and** the previous valid `TransformRegistry` remains active,
**and** in-flight and subsequent requests continue to use the previously valid specs.

---

## S-002-31: Concurrent Reload During Active Transform

**Given** `reloadIntervalSec = 30` in the plugin configuration,
**and** a transform is currently in-flight (being processed by a request thread),
**when** the reload timer fires and swaps the `AtomicReference<TransformRegistry>`,
**then** the in-flight transform completes using its snapshot of the old registry
(Java reference semantics guarantee this),
**and** the next request uses the new registry,
**and** no data corruption, locking, or request failure occurs.

> **Test:** Trigger reload in a background thread while a slow transform is executing.

---

## S-002-32: Non-JSON Response Body

**Given** a backend returns a `text/html` response body,
**when** `wrapResponse()` attempts to parse the body as JSON,
**then** the parse fails gracefully and falls back to `MessageBody.empty()` body,
**and** the adapter sets `bodyParseFailed = true`,
**and** response-direction transforms can still operate on headers and status code,
**and** body transforms are skipped — the original `text/html` body is forwarded
unchanged to the client (Q-003, Option A).

> **Outcome:** Body is passed through unmodified (adapter skip guard). Header/status
> transforms produce SUCCESS if they modify anything, PASSTHROUGH otherwise.

---

## S-002-33: JMX Metrics Opt-In

**Given** an admin configures `enableJmxMetrics = true` in the plugin configuration,
**when** `configure()` is called,
**then** the adapter registers a JMX MBean at
`io.messagexform:type=TransformMetrics,instance=<pluginName>`,
**and** JConsole shows the MBean under the `io.messagexform` domain,
**and** counters (successCount, errorCount, passthroughCount, etc.) increment
per request,
**and** the admin can invoke `resetMetrics()` via JConsole to zero all counters.

**When** the admin later toggles `enableJmxMetrics = false` and reconfigures,
**then** the MBean is unregistered,
**and** JConsole no longer shows it.

---

## S-002-34: JMX Metrics Disabled (Default)

**Given** `enableJmxMetrics` is not set (defaults to `false`),
**when** the adapter is configured and processing requests,
**then** no JMX MBean is registered,
**and** there is zero JMX overhead,
**and** SLF4J logging remains operational (INFO-level transform results),
**and** `pingaccess_engine_audit.log` records per-transaction timing automatically.

---

## S-002-35: PA-Specific Non-Standard Status Codes Passthrough

**Given** a backend returns HTTP status 277 (`ALLOWED` — PingAccess internal) or
477 (`REQUEST_BODY_REQUIRED` — PingAccess internal),
**and** a response-direction transform spec is loaded,
**when** `MessageTransformRule.handleResponse()` processes the exchange,
**then** the adapter does NOT map, rewrite, or reject the non-standard status code,
**and** the status code is passed through unchanged to downstream rules,
**and** response body/header transforms (if any) apply normally alongside the
non-standard status,
**and** `$status` in JSLT expressions resolves to the non-standard integer value
(277 or 477).

> **Constraint 9 coverage:** PingAccess uses non-standard HTTP status codes
> internally. The adapter must not assume standard HTTP semantics for status codes.

---

## S-002-36: Runtime Version Mismatch Warning

**Given** the adapter was compiled for PingAccess `9.0.1` (from
`pa-compiled-versions.properties`),
**and** runtime PingAccess reports version `9.2.0`,
**when** `MessageTransformRule.configure()` initializes `PaVersionGuard`,
**then** the adapter logs a WARN message indicating compiled/runtime mismatch,
**and** the warning includes remediation text to deploy adapter version
`9.2.0.x` for that PingAccess instance,
**and** the rule remains active (no fail-fast or startup abort).

> **ADR-0035 coverage:** Validates the simplified misdeployment guard contract:
> mismatch produces a WARN with clear remediation, not severity grading.

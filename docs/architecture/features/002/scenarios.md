# Feature 002 – PingAccess Adapter: Scenarios

> Each scenario is a testable contract. Scenarios prefixed `S-002-` map to the
> spec's Branch & Scenario Matrix and are referenced by task IDs during
> implementation.

---

## S-002-01: Request Body Transform

**Given** a POST request to `/api/users` with JSON body `{"name": "ivan"}` passes
through PingAccess to a backend site,
**and** a transform spec is loaded that matches `POST /api/users` direction
`request`,
**and** the spec rewrites the body to `{"username": "ivan", "source": "pa"}`,
**when** the `MessageTransformRule.handleRequest()` processes the exchange,
**then** the backend receives the transformed body `{"username": "ivan", "source": "pa"}`,
**and** the `Content-Length` header is auto-updated to match the new body size,
**and** `Outcome.CONTINUE` is returned.

---

## S-002-02: Response Body Transform

**Given** a backend returns status 200 with JSON body `{"result": "ok", "debug_token": "abc123"}`,
**and** a transform spec matches `GET /api/status` direction `response`,
**and** the spec removes the `debug_token` field,
**when** `MessageTransformRule.handleResponse()` processes the exchange,
**then** the client receives `{"result": "ok"}`,
**and** the `Content-Length` is updated.

---

## S-002-03: Bidirectional Transform

**Given** a transform spec has both `request` and `response` direction transforms
for `POST /api/auth`,
**when** a POST request flows through the rule,
**then** `handleRequest()` transforms the request body before forwarding,
**and** `handleResponse()` transforms the response body before returning to the
client,
**and** both transformations are independent (no shared state between phases).

---

## S-002-04: Header Transform

**Given** a transform spec adds header `X-Transformed: true` and removes header
`X-Internal-Debug` on the request,
**when** `handleRequest()` processes the exchange,
**then** the forwarded request has `X-Transformed: true` added,
**and** the `X-Internal-Debug` header is removed,
**and** original headers not targeted by the spec are preserved unchanged.

---

## S-002-05: Status Code Transform

**Given** a backend returns status 404 with empty body,
**and** a spec matches the response and maps status 404 to 200 with synthetic body
`{"found": false, "fallback": true}`,
**when** `handleResponse()` processes the exchange,
**then** the response status is changed to 200 via `setStatus(HttpStatus.forCode(200))`,
**and** the body is replaced with the synthetic JSON.

---

## S-002-06: URL Rewrite

**Given** a transform spec rewrites request path from `/legacy/users` to
`/api/v2/users`,
**when** `handleRequest()` processes the exchange,
**then** `exchange.getRequest().setUri()` is called with the new path,
**and** the original query string is preserved.

---

## S-002-07: Empty Body

**Given** a GET request with no body arrives at the rule,
**when** `wrapRequest()` is called,
**then** `Message.body()` is `NullNode`,
**and** if no spec matches a bodyless request, the exchange passes through
unmodified.

---

## S-002-08: Non-JSON Body

**Given** a POST request with `Content-Type: text/plain` and body `"hello world"`,
**when** `wrapRequest()` attempts JSON parse,
**then** the parse fails gracefully,
**and** `Message.body()` is `NullNode`,
**and** a warning is logged,
**and** the spec can still match and transform headers (non-body fields).

---

## S-002-09: Profile Matching

**Given** an active profile filters transforms to paths matching `/api/v1/**`,
**and** a request arrives for `POST /api/v1/users`,
**when** `handleRequest()` processes the exchange,
**then** the matching transform spec is applied.

**but given** a request arrives for `POST /admin/settings`,
**when** `handleRequest()` processes the exchange,
**then** no transform is applied (profile doesn't match), exchange passes through.

---

## S-002-10: No Matching Spec

**Given** no transform spec matches `GET /favicon.ico`,
**when** `handleRequest()` processes the exchange,
**then** no transformation is applied,
**and** `Outcome.CONTINUE` is returned,
**and** no error or warning is logged (this is a normal case).

---

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
**then** `Message.sessionContext()` contains `{"subject": "bjensen", "trackingId": "tx-001", ...}`,
**and** the JSLT expression resolves `$session.subject` to `"bjensen"`.

---

## S-002-14: No Identity (Unauthenticated)

**Given** the PingAccess Exchange has no Identity (unauthenticated request),
**when** `wrapRequest()` is called,
**then** `Message.sessionContext()` is `null`,
**and** JSLT expressions referencing `$session` evaluate to `null` without error.

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
> not nested under `$context`. See `TransformContext.cookiesAsJson()` for the
> binding implementation.

---

## S-002-23: Query Param Access in JSLT

**Given** a request to `/api/users?page=2&limit=10`,
**and** a transform spec uses `$queryParams.page`,
**when** `handleRequest()` processes the exchange,
**then** the JSLT expression resolves to `"2"`.

> **Note:** The JSLT engine binds query params as a top-level `$queryParams`
> variable, not nested under `$context`. See `TransformContext.queryParamsAsJson()`.

---

## S-002-24: Shadow JAR Correctness

**Given** the shadow JAR is built via `./gradlew :adapter-pingaccess:shadowJar`,
**when** deployed to a PingAccess 9.0 instance,
**then** no `ClassNotFoundException` or `NoClassDefFoundError` occurs at runtime,
**and** the shadow JAR does NOT contain PA SDK classes
(`com.pingidentity.pa.sdk.*`),
**and** the shadow JAR DOES contain all core + transitive dependencies (Jackson,
JSLT, SnakeYAML, JSON Schema Validator).

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
**then** the parse fails gracefully and falls back to `NullNode` body,
**and** response-direction transforms can still operate on headers and status code,
**and** the body is passed through unmodified.

> **Outcome:** PASSTHROUGH for body transforms, SUCCESS for header-only transforms.

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

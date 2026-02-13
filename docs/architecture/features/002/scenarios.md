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

```yaml
scenario: S-002-11
name: error-mode-pass-through
description: >
  When errorMode is PASS_THROUGH and a JSLT expression fails at runtime,
  the original request body is forwarded unchanged and CONTINUE is returned.
tags: [error-handling, pass-through, resilience]
refs: [FR-002-10, ADR-0013]

setup:
  config:
    errorMode: PASS_THROUGH
  exchange:
    request:
      method: POST
      uri: /api/process
      contentType: application/json
      body: '{"data": "value"}'
  specs:
    - id: broken-transform
      matches: "POST /api/process"
      direction: request
      expr: '.nonexistent.deep.path.boom()'  # causes runtime error

trigger: handleRequest

assertions:
  - description: warning is logged with error details
    expect: log.contains("WARN", "transform error")
  - description: original request body forwarded unchanged (copy-on-wrap)
    expect: forwarded.body == '{"data": "value"}'
  - description: outcome is CONTINUE (pipeline continues)
    expect: outcome == CONTINUE
```

---

## S-002-12: Error Mode DENY

```yaml
scenario: S-002-12
name: error-mode-deny
description: >
  When errorMode is DENY and a JSLT expression fails at runtime,
  the pipeline is halted with an RFC 9457 Problem Detail response.
tags: [error-handling, deny, security]
refs: [FR-002-10, FR-002-11, ADR-0013]

setup:
  config:
    errorMode: DENY
  exchange:
    request:
      method: POST
      uri: /api/process
      contentType: application/json
      body: '{"data": "value"}'
  specs:
    - id: broken-transform
      matches: "POST /api/process"
      direction: request
      expr: '.nonexistent.deep.path.boom()'  # causes runtime error

trigger: handleRequest

assertions:
  - description: warning/error is logged
    expect: log.contains("WARN" or "ERROR", "transform error")
  - description: outcome is RETURN (halts PingAccess pipeline)
    expect: outcome == RETURN
  - description: response body is RFC 9457 Problem Detail
    expect: response.body.type == "about:blank"
  - description: problem detail status is 502
    expect: response.body.status == 502
```

---

## S-002-13: Session Context in JSLT

```yaml
scenario: S-002-13
name: session-context-in-jslt
description: >
  Authenticated exchange's Identity attributes are bound as $session
  in JSLT. JSLT expressions can access session fields like $session.subject.
tags: [session, identity, jslt-context]
refs: [FR-002-06, FR-002-13, ADR-0030]

setup:
  exchange:
    request:
      method: POST
      uri: /api/data
      contentType: application/json
      body: '{"action": "fetch"}'
    identity:
      subject: "bjensen"
      trackingId: "tx-001"
      attributes:
        email: "bjensen@example.com"
        groups: ["admins", "users"]
  specs:
    - id: enrich-with-session
      matches: "POST /api/data"
      direction: request
      expr: '. + {"requestedBy": $session.subject}'

trigger: handleRequest

assertions:
  - description: Message.session() contains identity attributes
    expect: message.session().get("subject") == "bjensen"
  - description: $session.subject resolves in JSLT
    expect: forwarded.body contains '"requestedBy": "bjensen"'
  - description: $session.trackingId is available
    expect: message.session().get("trackingId") == "tx-001"
```

---

## S-002-14: No Identity (Unauthenticated)

```yaml
scenario: S-002-14
name: unauthenticated-empty-session
description: >
  Unauthenticated exchange (no Identity) produces SessionContext.empty().
  $session is {} (empty object) in JSLT; field access returns null
  per key-absent semantics.
tags: [session, unauthenticated, edge-case]
refs: [FR-002-06, FR-002-13]

setup:
  exchange:
    request:
      method: GET
      uri: /api/public
    identity: null  # unauthenticated — no Identity on exchange

trigger: handleRequest

assertions:
  - description: Message.session() is SessionContext.empty()
    expect: message.session() == SessionContext.empty()
  - description: $session evaluates to {} (empty object) in JSLT
    expect: jslt.eval("$session") == {}
  - description: field access $session.subject returns null without error
    expect: jslt.eval("$session.subject") == null
  - description: no error or warning logged
    expect: log.absent("ERROR")
```

## S-002-15: Multiple Specs Loaded

```yaml
scenario: S-002-15
name: multiple-specs-routing
description: >
  When multiple specs are loaded, only the matching spec is applied
  per request. Non-matching specs are ignored.
tags: [matching, routing, multi-spec]
refs: [FR-002-08]

setup:
  exchange:
    request:
      method: POST
      uri: /api/users
      contentType: application/json
      body: '{"name": "ivan"}'
  specs:
    - id: user-transform
      matches: "POST /api/users"
      direction: request
      expr: '{"username": .name}'
    - id: status-transform
      matches: "GET /api/status"
      direction: response
      expr: '{"up": true}'

trigger: handleRequest

assertions:
  - description: Spec A (user-transform) is applied
    expect: forwarded.body == '{"username": "ivan"}'
  - description: Spec B (status-transform) is NOT applied
    expect: spec("status-transform").invocations == 0
```

```yaml
# Reverse case: GET /api/status triggers Spec B
scenario: S-002-15b
name: multiple-specs-routing-reverse
description: >
  Same multi-spec setup; GET /api/status triggers Spec B only.
tags: [matching, routing, multi-spec]
refs: [FR-002-08]

setup:
  exchange:
    request:
      method: GET
      uri: /api/status
    response:
      statusCode: 200
      contentType: application/json
      body: '{"status": "running", "uptime": 9999}'
  specs:
    - id: user-transform
      matches: "POST /api/users"
      direction: request
      expr: '{"username": .name}'
    - id: status-transform
      matches: "GET /api/status"
      direction: response
      expr: '{"up": true}'

trigger: handleResponse

assertions:
  - description: Spec B (status-transform) is applied
    expect: response.body == '{"up": true}'
  - description: Spec A (user-transform) is NOT applied
    expect: spec("user-transform").invocations == 0
```

---

## S-002-16: Large Body (64 KB)

```yaml
scenario: S-002-16
name: large-body-performance
description: >
  A 64 KB JSON body is transformed within the eval budget. No OOM.
  Adapter overhead (excluding engine time) is < 10 ms.
tags: [performance, large-body, nfr]
refs: [NFR-002-01]

setup:
  exchange:
    request:
      method: POST
      uri: /api/bulk
      contentType: application/json
      body: "<64 KB JSON object>"  # generated at test time
  specs:
    - id: bulk-transform
      matches: "POST /api/bulk"
      direction: request
      expr: '. + {"processed": true}'

trigger: handleRequest

assertions:
  - description: transform completes within eval budget (5000 ms default)
    expect: duration < 5000
  - description: no OutOfMemoryError
    expect: error.absent("OutOfMemoryError")
  - description: adapter overhead (excluding engine) < 10 ms
    expect: adapterOverhead < 10
```

---

## S-002-17: Plugin Configuration via Admin UI

```yaml
scenario: S-002-17
name: plugin-configuration-valid
description: >
  Admin creates a Message Transform rule in PingAccess admin console.
  configure() initializes engine and loads specs from the specified directory.
tags: [config, lifecycle, admin-ui]
refs: [FR-002-09, FR-002-15]

setup:
  config:
    specsDir: /opt/specs
    errorMode: PASS_THROUGH
    enableJmxMetrics: false

trigger: configure

assertions:
  - description: configure() receives MessageTransformConfig with JSON config
    expect: config.specsDir == "/opt/specs"
  - description: engine initializes successfully
    expect: engine.initialized == true
  - description: specs are loaded from /opt/specs
    expect: engine.specCount >= 0
```

---

## S-002-18: Invalid Spec Directory

```yaml
scenario: S-002-18
name: invalid-spec-directory
description: >
  Configuring a non-existent specsDir causes ValidationException.
  The rule is not activated.
tags: [config, validation, error]
refs: [FR-002-09]

setup:
  config:
    specsDir: /nonexistent/path
    errorMode: PASS_THROUGH

trigger: configure

assertions:
  - description: ValidationException is thrown
    expect: throws ValidationException
  - description: error message indicates directory doesn't exist
    expect: exception.message contains "/nonexistent/path"
  - description: rule is NOT activated (PA shows error in admin UI)
    expect: rule.active == false
```

---

## S-002-19: Plugin SPI Registration

```yaml
scenario: S-002-19
name: plugin-spi-registration
description: >
  Shadow JAR contains the SPI service file. After deploy and restart,
  the rule type appears in PingAccess admin console.
tags: [spi, deployment, lifecycle]
refs: [FR-002-15, NFR-002-03]

setup:
  state:
    jar_contains:
      - META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor
    service_file_content: io.messagexform.pingaccess.MessageTransformRule

trigger: deploy

assertions:
  - description: SPI service file is present in shadow JAR
    expect: jar.contains("META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor")
  - description: service file declares the correct implementation class
    expect: serviceFile.content == "io.messagexform.pingaccess.MessageTransformRule"
  - description: rule type appears in PingAccess admin console
    expect: adminUI.ruleTypes contains "Message Transform"
```

---

## S-002-20: Thread Safety

```yaml
scenario: S-002-20
name: thread-safety
description: >
  The same MessageTransformRule instance handles 10 concurrent requests
  with no data corruption or shared mutable state access.
tags: [threading, concurrency, nfr]
refs: [NFR-002-02, FR-002-03]

setup:
  config:
    errorMode: PASS_THROUGH
  exchange:
    request:
      method: POST
      uri: /api/data
      contentType: application/json
      body: '{"id": "${threadId}"}'  # unique per thread
  specs:
    - id: data-transform
      matches: "POST /api/data"
      direction: request
      expr: '. + {"transformed": true}'
  concurrency:
    threads: 10
    iterations: 100

trigger: handleRequest (concurrent)

assertions:
  - description: no data corruption across threads
    expect: each response contains its own threadId
  - description: each request gets independent transformation results
    expect: all 1000 results are correct
  - description: no shared mutable state accessed
    expect: no ConcurrentModificationException or race condition
```

---

## S-002-21: ExchangeProperty Metadata

```yaml
scenario: S-002-21
name: exchange-property-metadata
description: >
  After a successful transform, the adapter stores a TransformResultSummary
  as an ExchangeProperty for downstream rules and logging.
tags: [metadata, exchange-property, observability]
refs: [FR-002-07]

setup:
  exchange:
    request:
      method: POST
      uri: /api/data
      contentType: application/json
      body: '{"data": "value"}'
  specs:
    - id: data-transform
      matches: "POST /api/data"
      direction: request
      expr: '. + {"enriched": true}'

trigger: handleRequest

assertions:
  - description: ExchangeProperty contains TransformResultSummary
    expect: exchange.getProperty(TRANSFORM_RESULT) != null
  - description: specId is set
    expect: result.specId == "data-transform"
  - description: specVersion is set
    expect: result.specVersion != null
  - description: direction is REQUEST
    expect: result.direction == "REQUEST"
  - description: durationMs is populated
    expect: result.durationMs >= 0
  - description: outcome is SUCCESS
    expect: result.outcome == "SUCCESS"
  - description: errorType is null (no error)
    expect: result.errorType == null
  - description: errorMessage is null (no error)
    expect: result.errorMessage == null
```

## S-002-22: Cookie Access in JSLT

```yaml
scenario: S-002-22
name: cookie-access-in-jslt
description: >
  Cookies from the request are bound as $cookies in JSLT.
  The TransformContext.cookies() accessor provides a first-value
  Map<String, String>.
tags: [jslt-context, cookies, binding]
refs: [FR-002-13, FR-001-13]

setup:
  exchange:
    request:
      method: GET
      uri: /api/dashboard
      cookies:
        session_token: "abc123"
        preference: "dark-mode"
  specs:
    - id: cookie-check
      matches: "GET /api/dashboard"
      direction: request
      expr: '. + {"token": $cookies.session_token}'

trigger: handleRequest

assertions:
  - description: $cookies.session_token resolves to cookie value
    expect: jslt.eval("$cookies.session_token") == "abc123"
  - description: cookie value is embedded in transformed body
    expect: forwarded.body contains '"token": "abc123"'
```

> **Note:** Cookies are bound as a top-level `$cookies` variable, not nested
> under `$context`. JSON conversion is handled internally by the engine.

---

## S-002-23: Query Param Access in JSLT

```yaml
scenario: S-002-23
name: query-param-access-in-jslt
description: >
  Query parameters are bound as $queryParams in JSLT. First-value
  semantics for multi-valued params.
tags: [jslt-context, query-params, binding]
refs: [FR-002-13, FR-001-13]

setup:
  exchange:
    request:
      method: GET
      uri: /api/users?page=2&limit=10
  specs:
    - id: paginate
      matches: "GET /api/users"
      direction: request
      expr: '. + {"requestedPage": $queryParams.page}'

trigger: handleRequest

assertions:
  - description: $queryParams.page resolves to "2"
    expect: jslt.eval("$queryParams.page") == "2"
  - description: $queryParams.limit resolves to "10"
    expect: jslt.eval("$queryParams.limit") == "10"
```

> **Note:** Query params are bound as a top-level `$queryParams` variable.
> First-value `Map<String, String>` — JSON conversion handled internally.

---

## S-002-24: Shadow JAR Correctness

```yaml
scenario: S-002-24
name: shadow-jar-correctness
description: >
  The shadow JAR bundles all required dependencies, excludes PA-provided
  classes, and works without runtime classpath errors on PA 9.0.
tags: [build, shadow-jar, deployment]
refs: [FR-002-09, NFR-002-03, ADR-0031]

setup:
  state:
    jar_built_via: "./gradlew :adapter-pingaccess:shadowJar"
    deploy_target: "PingAccess 9.0 instance"

trigger: deploy

assertions:
  - description: no ClassNotFoundException at runtime
    expect: error.absent("ClassNotFoundException")
  - description: no NoClassDefFoundError at runtime
    expect: error.absent("NoClassDefFoundError")
  - description: JAR does NOT contain PA SDK classes
    expect: jar.absent("com/pingidentity/pa/sdk/**")
  - description: JAR contains core + transitive deps (JSLT, SnakeYAML, etc.)
    expect: jar.contains("com/schibsted/spt/data/jslt/**")
  - description: JAR contains core's relocated Jackson
    expect: jar.contains("io/messagexform/internal/jackson/**")
  - description: JAR does NOT contain PA-provided Jackson
    expect: jar.absent("com/fasterxml/jackson/**")
```

---

## S-002-25: OAuth Context in JSLT

```yaml
scenario: S-002-25
name: oauth-context-in-jslt
description: >
  Identity's OAuth token metadata (clientId, scopes) is merged flat
  into $session at layer 2. No $session.oauth nesting.
tags: [session, oauth, jslt-context]
refs: [FR-002-06, FR-002-13]

setup:
  exchange:
    request:
      method: POST
      uri: /api/data
      contentType: application/json
      body: '{"action": "fetch"}'
    identity:
      subject: "bjensen"
      oauth:
        clientId: "my-app"
        scopes: ["openid", "email"]

trigger: handleRequest

assertions:
  - description: $session.clientId resolves to OAuth client ID
    expect: jslt.eval("$session.clientId") == "my-app"
  - description: $session.scopes resolves to scope list
    expect: jslt.eval("$session.scopes") == ["openid", "email"]
  - description: no $session.oauth nesting (flat merge)
    expect: jslt.eval("$session.oauth") == null
```

> **Note:** OAuth metadata is merged at layer 2 (see FR-002-06). The
> `$session` namespace is flat by design.

---

## S-002-26: Session State in JSLT

```yaml
scenario: S-002-26
name: session-state-in-jslt
description: >
  Session state attributes (stored by prior rules via SessionStateSupport)
  are merged flat into $session at layer 4 (highest precedence).
tags: [session, session-state, jslt-context]
refs: [FR-002-06, FR-002-13]

setup:
  exchange:
    request:
      method: POST
      uri: /api/authz
      contentType: application/json
      body: '{"resource": "/protected"}'
    identity:
      subject: "bjensen"
    state:
      sessionState:
        authzCache: "PERMIT"

trigger: handleRequest

assertions:
  - description: $session.authzCache resolves to session state value
    expect: jslt.eval("$session.authzCache") == "PERMIT"
  - description: no $session.sessionState nesting (flat merge)
    expect: jslt.eval("$session.sessionState") == null
```

> **Note:** Session state attributes are layer 4 (highest precedence).
> They override lower-layer attributes with the same key.

---

## S-002-27: Prior Rule URI Rewrite

```yaml
scenario: S-002-27
name: prior-rule-uri-rewrite
description: >
  When an upstream rule rewrites the URI before the adapter executes,
  the adapter wraps the rewritten URI, not the original client URI.
  Spec matching uses the rewritten path.
tags: [uri, prior-rules, interceptor-chain]
refs: [FR-002-01, FR-002-12]

setup:
  exchange:
    request:
      method: GET
      uri: /new/path?q=test  # rewritten by prior ParameterRule
    state:
      originalUri: /old/path?q=test  # exchange.getOriginalRequestUri()
  specs:
    - id: new-path-transform
      matches: "GET /new/**"
      direction: request
      expr: '. + {"routed": true}'

trigger: handleRequest

assertions:
  - description: adapter wraps the rewritten URI
    expect: message.requestPath() == "/new/path"
  - description: spec matches against rewritten URI
    expect: spec("new-path-transform") is applied
  - description: original URI is NOT used for matching
    expect: message.requestPath() != "/old/path"
```

> **Note:** `Request.getUri()` reflects modifications by prior rules in the
> interceptor chain. `Exchange.getOriginalRequestUri()` retains the original.

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

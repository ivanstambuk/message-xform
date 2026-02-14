# Feature 004 – Standalone HTTP Proxy Mode: Scenarios

| Field | Value |
|-------|-------|
| Status | Complete |
| Last updated | 2026-02-14 |
| Linked spec | `docs/architecture/features/004/spec.md` |
| Format | Behavioral Contract (see `docs/architecture/spec-guidelines/scenarios-format.md`) |

> Guardrail: Each scenario is a **testable contract** expressed as structured
> YAML. Scenarios serve as integration test fixtures — they can be loaded or
> parsed directly by test infrastructure.

---

## Format

Each scenario follows the behavioral contract schema and includes mandatory
`refs` links to FR/NFR requirements.

## Category 1: Basic Proxy

### S-004-01: Passthrough GET

```yaml
scenario: S-004-01
name: passthrough-get
description: >
  Passthrough GET.
tags: [standalone-proxy, basic_proxy]
refs: [FR-004-01, FR-004-35]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Passthrough GET"
```

---

### S-004-02: Passthrough POST with body

```yaml
scenario: S-004-02
name: passthrough-post-with-body
description: >
  Passthrough POST with body.
tags: [standalone-proxy, basic_proxy]
refs: [FR-004-01, FR-004-35]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Passthrough POST with body"
```

---

### S-004-03: Method forwarding (7 methods)

```yaml
scenario: S-004-03
name: method-forwarding-7-methods
description: >
  Method forwarding (7 methods).
tags: [standalone-proxy, basic_proxy]
refs: [FR-004-05]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Method forwarding (7 methods)"
```

---

### S-004-04: Header forwarding (hop-by-hop stripped)

```yaml
scenario: S-004-04
name: header-forwarding-hop-by-hop-stripped
description: >
  Header forwarding (hop-by-hop stripped).
tags: [standalone-proxy, basic_proxy]
refs: [FR-004-04]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Header forwarding (hop-by-hop stripped)"
```

---

### S-004-05: Query string forwarding

```yaml
scenario: S-004-05
name: query-string-forwarding
description: >
  Query string forwarding.
tags: [standalone-proxy, basic_proxy]
refs: [FR-004-04]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Query string forwarding"
```

---

### S-004-06: Path forwarding (nested)

```yaml
scenario: S-004-06
name: path-forwarding-nested
description: >
  Path forwarding (nested).
tags: [standalone-proxy, basic_proxy]
refs: [FR-004-04]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Path forwarding (nested)"
```

---

## Category 2: Request Transformation

### S-004-07: Request body transform

```yaml
scenario: S-004-07
name: request-body-transform
description: >
  Request body transform.
tags: [standalone-proxy, request_transformation]
refs: [FR-004-02]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Request body transform"
```

---

### S-004-08: Request header transform

```yaml
scenario: S-004-08
name: request-header-transform
description: >
  Request header transform.
tags: [standalone-proxy, request_transformation]
refs: [FR-004-02]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Request header transform"
```

---

### S-004-09: Request transform error → 502

```yaml
scenario: S-004-09
name: request-transform-error-to-502
description: >
  Request transform error → 502.
tags: [standalone-proxy, request_transformation]
refs: [FR-004-02, FR-004-23]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Request transform error → 502"
```

---

### S-004-10: Request with empty MessageBody

```yaml
scenario: S-004-10
name: request-with-nullnode-body
description: >
  Request with empty MessageBody.
tags: [standalone-proxy, request_transformation]
refs: [FR-004-02, FR-004-06]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Request with empty MessageBody"
```

---

## Category 3: Response Transformation

### S-004-11: Response body transform

```yaml
scenario: S-004-11
name: response-body-transform
description: >
  Response body transform.
tags: [standalone-proxy, response_transformation]
refs: [FR-004-03]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleResponse
assertions:
  - description: scenario contract is satisfied
    expect: "Response body transform"
```

---

### S-004-12: Response header transform

```yaml
scenario: S-004-12
name: response-header-transform
description: >
  Response header transform.
tags: [standalone-proxy, response_transformation]
refs: [FR-004-03]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleResponse
assertions:
  - description: scenario contract is satisfied
    expect: "Response header transform"
```

---

### S-004-13: Response status override (200→201)

```yaml
scenario: S-004-13
name: response-status-override-200-to-201
description: >
  Response status override (200→201).
tags: [standalone-proxy, response_transformation]
refs: [FR-004-03]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleResponse
assertions:
  - description: scenario contract is satisfied
    expect: "Response status override (200→201)"
```

---

### S-004-14: Response transform error → 502

```yaml
scenario: S-004-14
name: response-transform-error-to-502
description: >
  Response transform error → 502.
tags: [standalone-proxy, response_transformation]
refs: [FR-004-03, FR-004-23]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleResponse
assertions:
  - description: scenario contract is satisfied
    expect: "Response transform error → 502"
```

---

## Category 4: Bidirectional Transformation

### S-004-15: Bidirectional: both transformed

```yaml
scenario: S-004-15
name: bidirectional-both-transformed
description: >
  Bidirectional: both transformed.
tags: [standalone-proxy, bidirectional_transformation]
refs: [FR-004-02, FR-004-03]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest + handleResponse
assertions:
  - description: scenario contract is satisfied
    expect: "Bidirectional: both transformed"
```

---

### S-004-16: Request transform + response passthrough

```yaml
scenario: S-004-16
name: request-transform-response-passthrough
description: >
  Request transform + response passthrough.
tags: [standalone-proxy, bidirectional_transformation]
refs: [FR-004-02, FR-004-03]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest + handleResponse
assertions:
  - description: scenario contract is satisfied
    expect: "Request transform + response passthrough"
```

---

### S-004-17: Request passthrough + response transform

```yaml
scenario: S-004-17
name: request-passthrough-response-transform
description: >
  Request passthrough + response transform.
tags: [standalone-proxy, bidirectional_transformation]
refs: [FR-004-02, FR-004-03]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest + handleResponse
assertions:
  - description: scenario contract is satisfied
    expect: "Request passthrough + response transform"
```

---

## Category 5: Error Handling

### S-004-18: Backend unreachable → 502

```yaml
scenario: S-004-18
name: backend-unreachable-to-502
description: >
  Backend unreachable → 502.
tags: [standalone-proxy, error_handling]
refs: [FR-004-24]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Backend unreachable → 502"
```

---

### S-004-19: Backend timeout → 504

```yaml
scenario: S-004-19
name: backend-timeout-to-504
description: >
  Backend timeout → 504.
tags: [standalone-proxy, error_handling]
refs: [FR-004-25]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Backend timeout → 504"
```

---

### S-004-20: Backend connection refused → 502

```yaml
scenario: S-004-20
name: backend-connection-refused-to-502
description: >
  Backend connection refused → 502.
tags: [standalone-proxy, error_handling]
refs: [FR-004-24]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Backend connection refused → 502"
```

---

### S-004-21: Malformed JSON body → 400

```yaml
scenario: S-004-21
name: malformed-json-body-to-400
description: >
  Malformed JSON body → 400.
tags: [standalone-proxy, error_handling]
refs: [FR-004-26]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Malformed JSON body → 400"
```

---

### S-004-22: Request body too large → 413

```yaml
scenario: S-004-22
name: request-body-too-large-to-413
description: >
  Request body too large → 413.
tags: [standalone-proxy, error_handling]
refs: [FR-004-13]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Request body too large → 413"
```

---

### S-004-23: Unknown method → 405

```yaml
scenario: S-004-23
name: unknown-method-to-405
description: >
  Unknown method → 405.
tags: [standalone-proxy, error_handling]
refs: [FR-004-05]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Unknown method → 405"
```

---

### S-004-55: Non-JSON body + profile → 400

```yaml
scenario: S-004-55
name: non-json-body-profile-to-400
description: >
  Non-JSON body + profile → 400.
tags: [standalone-proxy, error_handling]
refs: [FR-004-26]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Non-JSON body + profile → 400"
```

---

### S-004-56: Response body too large → 502

```yaml
scenario: S-004-56
name: response-body-too-large-to-502
description: >
  Response body too large → 502.
tags: [standalone-proxy, error_handling]
refs: [FR-004-13]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleResponse
assertions:
  - description: scenario contract is satisfied
    expect: "Response body too large → 502"
```

---

## Category 6: Configuration

### S-004-24: YAML config loaded

```yaml
scenario: S-004-24
name: yaml-config-loaded
description: >
  YAML config loaded.
tags: [standalone-proxy, configuration]
refs: [FR-004-10]

setup:
  config:
    adapter: standalone
  specs: []
trigger: configure
assertions:
  - description: scenario contract is satisfied
    expect: "YAML config loaded"
```

---

### S-004-25: Env var override

```yaml
scenario: S-004-25
name: env-var-override
description: >
  Env var override.
tags: [standalone-proxy, configuration]
refs: [FR-004-11]

setup:
  config:
    adapter: standalone
  specs: []
trigger: configure
assertions:
  - description: scenario contract is satisfied
    expect: "Env var override"
```

---

### S-004-26: Missing config file → startup fails

```yaml
scenario: S-004-26
name: missing-config-file-to-startup-fails
description: >
  Missing config file → startup fails.
tags: [standalone-proxy, configuration]
refs: [FR-004-10]

setup:
  config:
    adapter: standalone
  specs: []
trigger: configure
assertions:
  - description: scenario contract is satisfied
    expect: "Missing config file → startup fails"
```

---

### S-004-27: Invalid config → descriptive error

```yaml
scenario: S-004-27
name: invalid-config-to-descriptive-error
description: >
  Invalid config → descriptive error.
tags: [standalone-proxy, configuration]
refs: [FR-004-12]

setup:
  config:
    adapter: standalone
  specs: []
trigger: configure
assertions:
  - description: scenario contract is satisfied
    expect: "Invalid config → descriptive error"
```

---

### S-004-28: Default values applied

```yaml
scenario: S-004-28
name: default-values-applied
description: >
  Default values applied.
tags: [standalone-proxy, configuration]
refs: [FR-004-12]

setup:
  config:
    adapter: standalone
  specs: []
trigger: configure
assertions:
  - description: scenario contract is satisfied
    expect: "Default values applied"
```

---

## Category 7: Hot Reload

### S-004-29: File watcher reload

```yaml
scenario: S-004-29
name: file-watcher-reload
description: >
  File watcher reload.
tags: [standalone-proxy, hot_reload]
refs: [FR-004-19]

setup:
  config:
    adapter: standalone
  specs: []
trigger: reload
assertions:
  - description: scenario contract is satisfied
    expect: "File watcher reload"
```

---

### S-004-30: Admin reload → 200

```yaml
scenario: S-004-30
name: admin-reload-to-200
description: >
  Admin reload → 200.
tags: [standalone-proxy, hot_reload]
refs: [FR-004-20]

setup:
  config:
    adapter: standalone
  specs: []
trigger: reload
assertions:
  - description: scenario contract is satisfied
    expect: "Admin reload → 200"
```

---

### S-004-31: Reload failure → old registry stays

```yaml
scenario: S-004-31
name: reload-failure-to-old-registry-stays
description: >
  Reload failure → old registry stays.
tags: [standalone-proxy, hot_reload]
refs: [FR-004-19, FR-004-20]

setup:
  config:
    adapter: standalone
  specs: []
trigger: reload
assertions:
  - description: scenario contract is satisfied
    expect: "Reload failure → old registry stays"
```

---

### S-004-32: Debounce rapid saves

```yaml
scenario: S-004-32
name: debounce-rapid-saves
description: >
  Debounce rapid saves.
tags: [standalone-proxy, hot_reload]
refs: [FR-004-19]

setup:
  config:
    adapter: standalone
  specs: []
trigger: reload
assertions:
  - description: scenario contract is satisfied
    expect: "Debounce rapid saves"
```

---

### S-004-33: Zero-downtime reload

```yaml
scenario: S-004-33
name: zero-downtime-reload
description: >
  Zero-downtime reload.
tags: [standalone-proxy, hot_reload]
refs: [FR-004-19, NFR-004-05]

setup:
  config:
    adapter: standalone
  specs: []
trigger: reload
assertions:
  - description: scenario contract is satisfied
    expect: "Zero-downtime reload"
```

---

## Category 8: Health and Readiness

### S-004-34: Health check UP

```yaml
scenario: S-004-34
name: health-check-up
description: >
  Health check UP.
tags: [standalone-proxy, health_and_readiness]
refs: [FR-004-21]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Health check UP"
```

---

### S-004-35: Readiness READY

```yaml
scenario: S-004-35
name: readiness-ready
description: >
  Readiness READY.
tags: [standalone-proxy, health_and_readiness]
refs: [FR-004-22]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Readiness READY"
```

---

### S-004-36: Readiness NOT_READY (startup)

```yaml
scenario: S-004-36
name: readiness-not-ready-startup
description: >
  Readiness NOT_READY (startup).
tags: [standalone-proxy, health_and_readiness]
refs: [FR-004-22]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Readiness NOT_READY (startup)"
```

---

### S-004-37: Readiness NOT_READY (backend down)

```yaml
scenario: S-004-37
name: readiness-not-ready-backend-down
description: >
  Readiness NOT_READY (backend down).
tags: [standalone-proxy, health_and_readiness]
refs: [FR-004-22]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Readiness NOT_READY (backend down)"
```

---

### S-004-38: Health/ready not transformed

```yaml
scenario: S-004-38
name: health-ready-not-transformed
description: >
  Health/ready not transformed.
tags: [standalone-proxy, health_and_readiness]
refs: [FR-004-21, FR-004-22]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Health/ready not transformed"
```

---

### S-004-70: Wildcard vs health/admin priority

```yaml
scenario: S-004-70
name: wildcard-vs-health-admin-priority
description: >
  Wildcard vs health/admin priority.
tags: [standalone-proxy, health_and_readiness]
refs: [FR-004-21, FR-004-22]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Wildcard vs health/admin priority"
```

---

## Category 9: TLS

### S-004-39: Inbound TLS

```yaml
scenario: S-004-39
name: inbound-tls
description: >
  Inbound TLS.
tags: [standalone-proxy, tls]
refs: [FR-004-14]

setup:
  config:
    adapter: standalone
  specs: []
trigger: configure
assertions:
  - description: scenario contract is satisfied
    expect: "Inbound TLS"
```

---

### S-004-40: Inbound mTLS

```yaml
scenario: S-004-40
name: inbound-mtls
description: >
  Inbound mTLS.
tags: [standalone-proxy, tls]
refs: [FR-004-15]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Inbound mTLS"
```

---

### S-004-41: Outbound TLS

```yaml
scenario: S-004-41
name: outbound-tls
description: >
  Outbound TLS.
tags: [standalone-proxy, tls]
refs: [FR-004-16]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Outbound TLS"
```

---

### S-004-42: Outbound mTLS

```yaml
scenario: S-004-42
name: outbound-mtls
description: >
  Outbound mTLS.
tags: [standalone-proxy, tls]
refs: [FR-004-17]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Outbound mTLS"
```

---

### S-004-43: TLS config error → startup fails

```yaml
scenario: S-004-43
name: tls-config-error-to-startup-fails
description: >
  TLS config error → startup fails.
tags: [standalone-proxy, tls]
refs: [FR-004-14, FR-004-16]

setup:
  config:
    adapter: standalone
  specs: []
trigger: configure
assertions:
  - description: scenario contract is satisfied
    expect: "TLS config error → startup fails"
```

---

## Category 10: Startup and Shutdown

### S-004-44: Clean startup

```yaml
scenario: S-004-44
name: clean-startup
description: >
  Clean startup.
tags: [standalone-proxy, startup_and_shutdown]
refs: [FR-004-27, NFR-004-01]

setup:
  config:
    adapter: standalone
  specs: []
trigger: deploy
assertions:
  - description: scenario contract is satisfied
    expect: "Clean startup"
```

---

### S-004-45: Startup failure (invalid spec)

```yaml
scenario: S-004-45
name: startup-failure-invalid-spec
description: >
  Startup failure (invalid spec).
tags: [standalone-proxy, startup_and_shutdown]
refs: [FR-004-27]

setup:
  config:
    adapter: standalone
  specs: []
trigger: deploy
assertions:
  - description: scenario contract is satisfied
    expect: "Startup failure (invalid spec)"
```

---

### S-004-46: Graceful shutdown

```yaml
scenario: S-004-46
name: graceful-shutdown
description: >
  Graceful shutdown.
tags: [standalone-proxy, startup_and_shutdown]
refs: [FR-004-28]

setup:
  config:
    adapter: standalone
  specs: []
trigger: shutdown
assertions:
  - description: scenario contract is satisfied
    expect: "Graceful shutdown"
```

---

### S-004-47: In-flight completion on shutdown

```yaml
scenario: S-004-47
name: in-flight-completion-on-shutdown
description: >
  In-flight completion on shutdown.
tags: [standalone-proxy, startup_and_shutdown]
refs: [FR-004-28]

setup:
  config:
    adapter: standalone
  specs: []
trigger: shutdown
assertions:
  - description: scenario contract is satisfied
    expect: "In-flight completion on shutdown"
```

---

## Category 11: Docker and Kubernetes

### S-004-48: Docker build (<150 MB)

```yaml
scenario: S-004-48
name: docker-build-150-mb
description: >
  Docker build (<150 MB).
tags: [standalone-proxy, docker_and_kubernetes]
refs: [FR-004-29, NFR-004-04]

setup:
  config:
    adapter: standalone
  specs: []
trigger: deploy
assertions:
  - description: scenario contract is satisfied
    expect: "Docker build (<150 MB)"
```

---

### S-004-49: Docker run + serve traffic

```yaml
scenario: S-004-49
name: docker-run-serve-traffic
description: >
  Docker run + serve traffic.
tags: [standalone-proxy, docker_and_kubernetes]
refs: [FR-004-29]

setup:
  config:
    adapter: standalone
  specs: []
trigger: deploy
assertions:
  - description: scenario contract is satisfied
    expect: "Docker run + serve traffic"
```

---

### S-004-50: Volume mount → specs loaded

```yaml
scenario: S-004-50
name: volume-mount-to-specs-loaded
description: >
  Volume mount → specs loaded.
tags: [standalone-proxy, docker_and_kubernetes]
refs: [FR-004-31]

setup:
  config:
    adapter: standalone
  specs: []
trigger: deploy
assertions:
  - description: scenario contract is satisfied
    expect: "Volume mount → specs loaded"
```

---

### S-004-51: Shadow JAR → starts clean

```yaml
scenario: S-004-51
name: shadow-jar-to-starts-clean
description: >
  Shadow JAR → starts clean.
tags: [standalone-proxy, docker_and_kubernetes]
refs: [FR-004-30]

setup:
  config:
    adapter: standalone
  specs: []
trigger: deploy
assertions:
  - description: scenario contract is satisfied
    expect: "Shadow JAR → starts clean"
```

---

## Category 12: Upstream Protocol

### S-004-52: HTTP/1.1 enforced

```yaml
scenario: S-004-52
name: http-1-1-enforced
description: >
  HTTP/1.1 enforced.
tags: [standalone-proxy, upstream_protocol]
refs: [FR-004-33]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "HTTP/1.1 enforced"
```

---

### S-004-53: Request Content-Length recalculated

```yaml
scenario: S-004-53
name: request-content-length-recalculated
description: >
  Request Content-Length recalculated.
tags: [standalone-proxy, upstream_protocol]
refs: [FR-004-34]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Request Content-Length recalculated"
```

---

### S-004-54: Response Content-Length recalculated

```yaml
scenario: S-004-54
name: response-content-length-recalculated
description: >
  Response Content-Length recalculated.
tags: [standalone-proxy, upstream_protocol]
refs: [FR-004-34]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleResponse
assertions:
  - description: scenario contract is satisfied
    expect: "Response Content-Length recalculated"
```

---

## Category 13: Forwarded Headers

### S-004-57: X-Forwarded-* added (default)

```yaml
scenario: S-004-57
name: x-forwarded-added-default
description: >
  X-Forwarded-* added (default).
tags: [standalone-proxy, forwarded_headers]
refs: [FR-004-36]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "X-Forwarded-* added (default)"
```

---

### S-004-58: X-Forwarded-* disabled

```yaml
scenario: S-004-58
name: x-forwarded-disabled
description: >
  X-Forwarded-* disabled.
tags: [standalone-proxy, forwarded_headers]
refs: [FR-004-36]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "X-Forwarded-* disabled"
```

---

### S-004-59: X-Forwarded-For appended

```yaml
scenario: S-004-59
name: x-forwarded-for-appended
description: >
  X-Forwarded-For appended.
tags: [standalone-proxy, forwarded_headers]
refs: [FR-004-36]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "X-Forwarded-For appended"
```

---

## Category 14: Dispatch Table Integration

### S-004-60: Request SUCCESS dispatch

```yaml
scenario: S-004-60
name: request-success-dispatch
description: >
  Request SUCCESS dispatch.
tags: [standalone-proxy, dispatch_table_integration]
refs: [FR-004-35]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest + handleResponse
assertions:
  - description: scenario contract is satisfied
    expect: "Request SUCCESS dispatch"
```

---

### S-004-61: Response SUCCESS dispatch

```yaml
scenario: S-004-61
name: response-success-dispatch
description: >
  Response SUCCESS dispatch.
tags: [standalone-proxy, dispatch_table_integration]
refs: [FR-004-35]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleResponse
assertions:
  - description: scenario contract is satisfied
    expect: "Response SUCCESS dispatch"
```

---

### S-004-62: Request ERROR dispatch → 502

```yaml
scenario: S-004-62
name: request-error-dispatch-to-502
description: >
  Request ERROR dispatch → 502.
tags: [standalone-proxy, dispatch_table_integration]
refs: [FR-004-35]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest + handleResponse
assertions:
  - description: scenario contract is satisfied
    expect: "Request ERROR dispatch → 502"
```

---

### S-004-63: Request PASSTHROUGH dispatch

```yaml
scenario: S-004-63
name: request-passthrough-dispatch
description: >
  Request PASSTHROUGH dispatch.
tags: [standalone-proxy, dispatch_table_integration]
refs: [FR-004-35]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest + handleResponse
assertions:
  - description: scenario contract is satisfied
    expect: "Request PASSTHROUGH dispatch"
```

---

### S-004-64: Response 204 → empty MessageBody

```yaml
scenario: S-004-64
name: response-204-to-nullnode-body
description: >
  Response 204 → empty MessageBody.
tags: [standalone-proxy, dispatch_table_integration]
refs: [FR-004-35, FR-004-06b]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleResponse
assertions:
  - description: scenario contract is satisfied
    expect: "Response 204 → empty MessageBody"
```

---

## Category 15: Concurrency

### S-004-65: 100 concurrent connections

```yaml
scenario: S-004-65
name: 100-concurrent-connections
description: >
  100 concurrent connections with benchmark telemetry for latency and load
  behavior.
tags: [standalone-proxy, concurrency]
refs: [NFR-004-02, NFR-004-03, NFR-004-06]

setup:
  config:
    adapter: standalone
  specs: []
trigger: benchmark
assertions:
  - description: scenario contract is satisfied
    expect: "100 concurrent connections"
```

---

### S-004-66: Concurrent reload during traffic

```yaml
scenario: S-004-66
name: concurrent-reload-during-traffic
description: >
  Concurrent reload during traffic.
tags: [standalone-proxy, concurrency]
refs: [FR-004-19, NFR-004-05]

setup:
  config:
    adapter: standalone
  specs: []
trigger: benchmark
assertions:
  - description: scenario contract is satisfied
    expect: "Concurrent reload during traffic"
```

---

## Category 16: Cookie Binding

### S-004-67: Cookie binding in JSLT

```yaml
scenario: S-004-67
name: cookie-binding-in-jslt
description: >
  Cookie binding in JSLT.
tags: [standalone-proxy, cookie_binding]
refs: [FR-004-37]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Cookie binding in JSLT"
```

---

### S-004-68: No cookies → empty map

```yaml
scenario: S-004-68
name: no-cookies-to-empty-map
description: >
  No cookies → empty map.
tags: [standalone-proxy, cookie_binding]
refs: [FR-004-37]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "No cookies → empty map"
```

---

### S-004-69: URL-encoded cookie value

```yaml
scenario: S-004-69
name: url-encoded-cookie-value
description: >
  URL-encoded cookie value.
tags: [standalone-proxy, cookie_binding]
refs: [FR-004-37]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "URL-encoded cookie value"
```

---

## Category 17: Request ID

### S-004-71: Request ID generated

```yaml
scenario: S-004-71
name: request-id-generated
description: >
  Request ID generated.
tags: [standalone-proxy, request_id]
refs: [FR-004-38, NFR-004-07]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Request ID generated"
```

---

### S-004-72: Request ID echoed

```yaml
scenario: S-004-72
name: request-id-echoed
description: >
  Request ID echoed.
tags: [standalone-proxy, request_id]
refs: [FR-004-38, NFR-004-07]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Request ID echoed"
```

---

### S-004-73: Request ID in error response

```yaml
scenario: S-004-73
name: request-id-in-error-response
description: >
  Request ID in error response.
tags: [standalone-proxy, request_id]
refs: [FR-004-38, NFR-004-07]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Request ID in error response"
```

---

## Category 18: Query Parameter Binding

### S-004-74: Query param binding in JSLT

```yaml
scenario: S-004-74
name: query-param-binding-in-jslt
description: >
  Query param binding in JSLT.
tags: [standalone-proxy, query_parameter_binding]
refs: [FR-004-39]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Query param binding in JSLT"
```

---

### S-004-75: No query string → empty map

```yaml
scenario: S-004-75
name: no-query-string-to-empty-map
description: >
  No query string → empty map.
tags: [standalone-proxy, query_parameter_binding]
refs: [FR-004-39]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "No query string → empty map"
```

---

### S-004-76: Multi-value query param

```yaml
scenario: S-004-76
name: multi-value-query-param
description: >
  Multi-value query param.
tags: [standalone-proxy, query_parameter_binding]
refs: [FR-004-39]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "Multi-value query param"
```

---

### S-004-77: URL-encoded query param

```yaml
scenario: S-004-77
name: url-encoded-query-param
description: >
  URL-encoded query param.
tags: [standalone-proxy, query_parameter_binding]
refs: [FR-004-39]

setup:
  config:
    adapter: standalone
  specs: []
trigger: handleRequest
assertions:
  - description: scenario contract is satisfied
    expect: "URL-encoded query param"
```

---

## Scenario Index

| ID | Name | Category | Tags |
|----|------|----------|------|
| S-004-01 | passthrough-get | Basic Proxy | standalone-proxy, basic_proxy |
| S-004-02 | passthrough-post-with-body | Basic Proxy | standalone-proxy, basic_proxy |
| S-004-03 | method-forwarding-7-methods | Basic Proxy | standalone-proxy, basic_proxy |
| S-004-04 | header-forwarding-hop-by-hop-stripped | Basic Proxy | standalone-proxy, basic_proxy |
| S-004-05 | query-string-forwarding | Basic Proxy | standalone-proxy, basic_proxy |
| S-004-06 | path-forwarding-nested | Basic Proxy | standalone-proxy, basic_proxy |
| S-004-07 | request-body-transform | Request Transformation | standalone-proxy, request_transformation |
| S-004-08 | request-header-transform | Request Transformation | standalone-proxy, request_transformation |
| S-004-09 | request-transform-error-to-502 | Request Transformation | standalone-proxy, request_transformation |
| S-004-10 | request-with-nullnode-body | Request Transformation | standalone-proxy, request_transformation |
| S-004-11 | response-body-transform | Response Transformation | standalone-proxy, response_transformation |
| S-004-12 | response-header-transform | Response Transformation | standalone-proxy, response_transformation |
| S-004-13 | response-status-override-200-to-201 | Response Transformation | standalone-proxy, response_transformation |
| S-004-14 | response-transform-error-to-502 | Response Transformation | standalone-proxy, response_transformation |
| S-004-15 | bidirectional-both-transformed | Bidirectional Transformation | standalone-proxy, bidirectional_transformation |
| S-004-16 | request-transform-response-passthrough | Bidirectional Transformation | standalone-proxy, bidirectional_transformation |
| S-004-17 | request-passthrough-response-transform | Bidirectional Transformation | standalone-proxy, bidirectional_transformation |
| S-004-18 | backend-unreachable-to-502 | Error Handling | standalone-proxy, error_handling |
| S-004-19 | backend-timeout-to-504 | Error Handling | standalone-proxy, error_handling |
| S-004-20 | backend-connection-refused-to-502 | Error Handling | standalone-proxy, error_handling |
| S-004-21 | malformed-json-body-to-400 | Error Handling | standalone-proxy, error_handling |
| S-004-22 | request-body-too-large-to-413 | Error Handling | standalone-proxy, error_handling |
| S-004-23 | unknown-method-to-405 | Error Handling | standalone-proxy, error_handling |
| S-004-55 | non-json-body-profile-to-400 | Error Handling | standalone-proxy, error_handling |
| S-004-56 | response-body-too-large-to-502 | Error Handling | standalone-proxy, error_handling |
| S-004-24 | yaml-config-loaded | Configuration | standalone-proxy, configuration |
| S-004-25 | env-var-override | Configuration | standalone-proxy, configuration |
| S-004-26 | missing-config-file-to-startup-fails | Configuration | standalone-proxy, configuration |
| S-004-27 | invalid-config-to-descriptive-error | Configuration | standalone-proxy, configuration |
| S-004-28 | default-values-applied | Configuration | standalone-proxy, configuration |
| S-004-29 | file-watcher-reload | Hot Reload | standalone-proxy, hot_reload |
| S-004-30 | admin-reload-to-200 | Hot Reload | standalone-proxy, hot_reload |
| S-004-31 | reload-failure-to-old-registry-stays | Hot Reload | standalone-proxy, hot_reload |
| S-004-32 | debounce-rapid-saves | Hot Reload | standalone-proxy, hot_reload |
| S-004-33 | zero-downtime-reload | Hot Reload | standalone-proxy, hot_reload |
| S-004-34 | health-check-up | Health and Readiness | standalone-proxy, health_and_readiness |
| S-004-35 | readiness-ready | Health and Readiness | standalone-proxy, health_and_readiness |
| S-004-36 | readiness-not-ready-startup | Health and Readiness | standalone-proxy, health_and_readiness |
| S-004-37 | readiness-not-ready-backend-down | Health and Readiness | standalone-proxy, health_and_readiness |
| S-004-38 | health-ready-not-transformed | Health and Readiness | standalone-proxy, health_and_readiness |
| S-004-70 | wildcard-vs-health-admin-priority | Health and Readiness | standalone-proxy, health_and_readiness |
| S-004-39 | inbound-tls | TLS | standalone-proxy, tls |
| S-004-40 | inbound-mtls | TLS | standalone-proxy, tls |
| S-004-41 | outbound-tls | TLS | standalone-proxy, tls |
| S-004-42 | outbound-mtls | TLS | standalone-proxy, tls |
| S-004-43 | tls-config-error-to-startup-fails | TLS | standalone-proxy, tls |
| S-004-44 | clean-startup | Startup and Shutdown | standalone-proxy, startup_and_shutdown |
| S-004-45 | startup-failure-invalid-spec | Startup and Shutdown | standalone-proxy, startup_and_shutdown |
| S-004-46 | graceful-shutdown | Startup and Shutdown | standalone-proxy, startup_and_shutdown |
| S-004-47 | in-flight-completion-on-shutdown | Startup and Shutdown | standalone-proxy, startup_and_shutdown |
| S-004-48 | docker-build-150-mb | Docker and Kubernetes | standalone-proxy, docker_and_kubernetes |
| S-004-49 | docker-run-serve-traffic | Docker and Kubernetes | standalone-proxy, docker_and_kubernetes |
| S-004-50 | volume-mount-to-specs-loaded | Docker and Kubernetes | standalone-proxy, docker_and_kubernetes |
| S-004-51 | shadow-jar-to-starts-clean | Docker and Kubernetes | standalone-proxy, docker_and_kubernetes |
| S-004-52 | http-1-1-enforced | Upstream Protocol | standalone-proxy, upstream_protocol |
| S-004-53 | request-content-length-recalculated | Upstream Protocol | standalone-proxy, upstream_protocol |
| S-004-54 | response-content-length-recalculated | Upstream Protocol | standalone-proxy, upstream_protocol |
| S-004-57 | x-forwarded-added-default | Forwarded Headers | standalone-proxy, forwarded_headers |
| S-004-58 | x-forwarded-disabled | Forwarded Headers | standalone-proxy, forwarded_headers |
| S-004-59 | x-forwarded-for-appended | Forwarded Headers | standalone-proxy, forwarded_headers |
| S-004-60 | request-success-dispatch | Dispatch Table Integration | standalone-proxy, dispatch_table_integration |
| S-004-61 | response-success-dispatch | Dispatch Table Integration | standalone-proxy, dispatch_table_integration |
| S-004-62 | request-error-dispatch-to-502 | Dispatch Table Integration | standalone-proxy, dispatch_table_integration |
| S-004-63 | request-passthrough-dispatch | Dispatch Table Integration | standalone-proxy, dispatch_table_integration |
| S-004-64 | response-204-to-nullnode-body | Dispatch Table Integration | standalone-proxy, dispatch_table_integration |
| S-004-65 | 100-concurrent-connections | Concurrency | standalone-proxy, concurrency |
| S-004-66 | concurrent-reload-during-traffic | Concurrency | standalone-proxy, concurrency |
| S-004-67 | cookie-binding-in-jslt | Cookie Binding | standalone-proxy, cookie_binding |
| S-004-68 | no-cookies-to-empty-map | Cookie Binding | standalone-proxy, cookie_binding |
| S-004-69 | url-encoded-cookie-value | Cookie Binding | standalone-proxy, cookie_binding |
| S-004-71 | request-id-generated | Request ID | standalone-proxy, request_id |
| S-004-72 | request-id-echoed | Request ID | standalone-proxy, request_id |
| S-004-73 | request-id-in-error-response | Request ID | standalone-proxy, request_id |
| S-004-74 | query-param-binding-in-jslt | Query Parameter Binding | standalone-proxy, query_parameter_binding |
| S-004-75 | no-query-string-to-empty-map | Query Parameter Binding | standalone-proxy, query_parameter_binding |
| S-004-76 | multi-value-query-param | Query Parameter Binding | standalone-proxy, query_parameter_binding |
| S-004-77 | url-encoded-query-param | Query Parameter Binding | standalone-proxy, query_parameter_binding |

## Coverage Matrix

Mapping of scenario IDs to requirement references.

| Requirement | Scenarios |
|-------------|-----------|
| **FR-004-01** | S-004-01, S-004-02 |
| **FR-004-02** | S-004-07, S-004-08, S-004-09, S-004-10, S-004-15, S-004-16, S-004-17 |
| **FR-004-03** | S-004-11, S-004-12, S-004-13, S-004-14, S-004-15, S-004-16, S-004-17 |
| **FR-004-04** | S-004-04, S-004-05, S-004-06 |
| **FR-004-05** | S-004-03, S-004-23 |
| **FR-004-06** | S-004-10 |
| **FR-004-06b** | S-004-64 |
| **FR-004-10** | S-004-24, S-004-26 |
| **FR-004-11** | S-004-25 |
| **FR-004-12** | S-004-27, S-004-28 |
| **FR-004-13** | S-004-22, S-004-56 |
| **FR-004-14** | S-004-39, S-004-43 |
| **FR-004-15** | S-004-40 |
| **FR-004-16** | S-004-41, S-004-43 |
| **FR-004-17** | S-004-42 |
| **FR-004-19** | S-004-29, S-004-31, S-004-32, S-004-33, S-004-66 |
| **FR-004-20** | S-004-30, S-004-31 |
| **FR-004-21** | S-004-34, S-004-38, S-004-70 |
| **FR-004-22** | S-004-35, S-004-36, S-004-37, S-004-38, S-004-70 |
| **FR-004-23** | S-004-09, S-004-14 |
| **FR-004-24** | S-004-18, S-004-20 |
| **FR-004-25** | S-004-19 |
| **FR-004-26** | S-004-21, S-004-55 |
| **FR-004-27** | S-004-44, S-004-45 |
| **FR-004-28** | S-004-46, S-004-47 |
| **FR-004-29** | S-004-48, S-004-49 |
| **FR-004-30** | S-004-51 |
| **FR-004-31** | S-004-50 |
| **FR-004-33** | S-004-52 |
| **FR-004-34** | S-004-53, S-004-54 |
| **FR-004-35** | S-004-01, S-004-02, S-004-60, S-004-61, S-004-62, S-004-63, S-004-64 |
| **FR-004-36** | S-004-57, S-004-58, S-004-59 |
| **FR-004-37** | S-004-67, S-004-68, S-004-69 |
| **FR-004-38** | S-004-71, S-004-72, S-004-73 |
| **FR-004-39** | S-004-74, S-004-75, S-004-76, S-004-77 |
| **NFR-004-01** | S-004-44 |
| **NFR-004-02** | S-004-65 |
| **NFR-004-03** | S-004-65 |
| **NFR-004-04** | S-004-48 |
| **NFR-004-05** | S-004-33, S-004-66 |
| **NFR-004-06** | S-004-65 |
| **NFR-004-07** | S-004-71, S-004-72, S-004-73 |

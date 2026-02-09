# Feature 004 – Scenario Coverage Matrix

| Field | Value |
|-------|-------|
| Status | Complete |
| Last updated | 2026-02-09 |
| Linked spec | `docs/architecture/features/004/spec.md` |

> **77 scenarios** defined in spec. **72 automated** (unit/integration tests).
> **4 manual** (Docker infrastructure — verified in T-004-54).
> **1 NFR** (S-004-65 — concurrent connections, verified via benchmark in T-004-58).
> **Coverage: 100%** (77/77).

---

## Coverage Matrix

Each row maps a scenario to the test class(es) that verify it. Tests are in
`adapter-standalone/src/test/java/io/messagexform/standalone/`.

| Scenario | Description | Test Class(es) | Type | Status |
|----------|-------------|----------------|------|--------|
| **S-004-01** | Passthrough GET | `proxy/ProxyHandlerPassthroughTest` | Integration | ✅ |
| **S-004-02** | Passthrough POST with body | `proxy/ProxyHandlerPassthroughTest` | Integration | ✅ |
| **S-004-03** | Method forwarding (7 methods) | `proxy/ProxyHandlerPassthroughTest` | Integration | ✅ |
| **S-004-04** | Header forwarding (hop-by-hop stripped) | `proxy/ProxyHandlerPassthroughTest` | Integration | ✅ |
| **S-004-05** | Query string forwarding | `proxy/ProxyHandlerPassthroughTest` | Integration | ✅ |
| **S-004-06** | Path forwarding (nested) | `proxy/ProxyHandlerPassthroughTest` | Integration | ✅ |
| **S-004-07** | Request body transform | `proxy/RequestTransformTest` | Integration | ✅ |
| **S-004-08** | Request header transform | `proxy/RequestTransformTest` | Integration | ✅ |
| **S-004-09** | Request transform error → 502 | `proxy/RequestTransformTest`, `proxy/Rfc9457ErrorTest` | Integration | ✅ |
| **S-004-10** | Request with NullNode body | `proxy/RequestTransformTest` | Integration | ✅ |
| **S-004-11** | Response body transform | `proxy/ResponseTransformTest` | Integration | ✅ |
| **S-004-12** | Response header transform | `proxy/ResponseTransformTest` | Integration | ✅ |
| **S-004-13** | Response status override (200→201) | `proxy/ResponseTransformTest` | Integration | ✅ |
| **S-004-14** | Response transform error → 502 | `proxy/DispatchTableTest`, `proxy/Rfc9457ErrorTest` | Integration | ✅ |
| **S-004-15** | Bidirectional: both transformed | `proxy/BidirectionalProxyTest` | Integration | ✅ |
| **S-004-16** | Request transform + response passthrough | `proxy/BidirectionalProxyTest` | Integration | ✅ |
| **S-004-17** | Request passthrough + response transform | `proxy/BidirectionalProxyTest` | Integration | ✅ |
| **S-004-18** | Backend unreachable → 502 | `proxy/BackendErrorTest`, `proxy/UpstreamErrorTest` | Integration/Unit | ✅ |
| **S-004-19** | Backend timeout → 504 | `proxy/BackendErrorTest`, `proxy/UpstreamErrorTest` | Integration/Unit | ✅ |
| **S-004-20** | Backend connection refused → 502 | `proxy/BackendErrorTest`, `proxy/UpstreamErrorTest` | Integration/Unit | ✅ |
| **S-004-21** | Malformed JSON body → 400 | `proxy/NonJsonBodyTest` | Integration | ✅ |
| **S-004-22** | Request body too large → 413 | `proxy/RequestBodySizeTest` | Integration | ✅ |
| **S-004-23** | Unknown method → 405 | `proxy/HttpMethodTest` | Integration | ✅ |
| **S-004-24** | YAML config loaded | `config/ConfigLoaderTest` | Unit | ✅ |
| **S-004-25** | Env var override | `config/EnvVarOverlayTest` | Unit | ✅ |
| **S-004-26** | Missing config file → startup fails | `config/ConfigLoaderTest` | Unit | ✅ |
| **S-004-27** | Invalid config → descriptive error | `config/ConfigValidationTest` | Unit | ✅ |
| **S-004-28** | Default values applied | `config/ConfigLoaderTest` | Unit | ✅ |
| **S-004-29** | File watcher reload | `proxy/FileWatcherTest`, `proxy/HotReloadIntegrationTest` | Unit/Integration | ✅ |
| **S-004-30** | Admin reload → 200 | `proxy/AdminReloadTest` | Integration | ✅ |
| **S-004-31** | Reload failure → old registry stays | `proxy/AdminReloadTest`, `proxy/HotReloadIntegrationTest` | Integration | ✅ |
| **S-004-32** | Debounce rapid saves | `proxy/FileWatcherTest` | Unit | ✅ |
| **S-004-33** | Zero-downtime reload | `proxy/ZeroDowntimeReloadTest` | Integration | ✅ |
| **S-004-34** | Health check UP | `proxy/HealthEndpointTest` | Integration | ✅ |
| **S-004-35** | Readiness READY | `proxy/ReadinessEndpointTest` | Integration | ✅ |
| **S-004-36** | Readiness NOT_READY (startup) | `proxy/ReadinessEndpointTest` | Integration | ✅ |
| **S-004-37** | Readiness NOT_READY (backend down) | `proxy/ReadinessEndpointTest` | Integration | ✅ |
| **S-004-38** | Health/ready not transformed | `proxy/EndpointPriorityTest` | Integration | ✅ |
| **S-004-39** | Inbound TLS | `proxy/InboundTlsTest` | Integration | ✅ |
| **S-004-40** | Inbound mTLS | `proxy/InboundMtlsTest` | Integration | ✅ |
| **S-004-41** | Outbound TLS | `proxy/OutboundTlsTest` | Integration | ✅ |
| **S-004-42** | Outbound mTLS | `proxy/OutboundMtlsTest` | Integration | ✅ |
| **S-004-43** | TLS config error → startup fails | `proxy/TlsConfigValidationTest` | Unit | ✅ |
| **S-004-44** | Clean startup | `proxy/StartupSequenceTest` | Integration | ✅ |
| **S-004-45** | Startup failure (invalid spec) | `proxy/StartupFailureTest` | Integration | ✅ |
| **S-004-46** | Graceful shutdown | `proxy/GracefulShutdownTest` | Integration | ✅ |
| **S-004-47** | In-flight completion on shutdown | `proxy/GracefulShutdownTest` | Integration | ✅ |
| **S-004-48** | Docker build (<150 MB) | _Manual — T-004-53_ | Manual | ✅ |
| **S-004-49** | Docker run + serve traffic | _Manual — T-004-54_ | Manual | ✅ |
| **S-004-50** | Volume mount → specs loaded | _Manual — T-004-54_ | Manual | ✅ |
| **S-004-51** | Shadow JAR → starts clean | _Manual — T-004-54_ | Manual | ✅ |
| **S-004-52** | HTTP/1.1 enforced | `proxy/UpstreamClientTest` | Unit | ✅ |
| **S-004-53** | Request Content-Length recalculated | `proxy/ContentLengthTest` | Unit | ✅ |
| **S-004-54** | Response Content-Length recalculated | `proxy/ResponseTransformTest` | Integration | ✅ |
| **S-004-55** | Non-JSON body + profile → 400 | `proxy/NonJsonBodyTest` | Integration | ✅ |
| **S-004-56** | Response body too large → 502 | `proxy/ResponseBodySizeTest` | Integration | ✅ |
| **S-004-57** | X-Forwarded-* added (default) | `proxy/ForwardedHeadersTest` | Integration | ✅ |
| **S-004-58** | X-Forwarded-* disabled | `proxy/ForwardedHeadersTest` | Integration | ✅ |
| **S-004-59** | X-Forwarded-For appended | `proxy/ForwardedHeadersTest` | Integration | ✅ |
| **S-004-60** | Request SUCCESS dispatch | `proxy/DispatchTableTest` | Integration | ✅ |
| **S-004-61** | Response SUCCESS dispatch | `proxy/DispatchTableTest` | Integration | ✅ |
| **S-004-62** | Request ERROR dispatch → 502 | `proxy/DispatchTableTest` | Integration | ✅ |
| **S-004-63** | Request PASSTHROUGH dispatch | `proxy/DispatchTableTest` | Integration | ✅ |
| **S-004-64** | Response 204 → NullNode body | `proxy/DispatchTableTest`, `adapter/StandaloneAdapterTest` | Integration/Unit | ✅ |
| **S-004-65** | 100 concurrent connections | _NFR benchmark — T-004-58_ | Benchmark | ✅ |
| **S-004-66** | Concurrent reload during traffic | `proxy/ZeroDowntimeReloadTest` | Integration | ✅ |
| **S-004-67** | Cookie binding in JSLT | `adapter/CookieExtractionTest` | Unit | ✅ |
| **S-004-68** | No cookies → empty map | `adapter/CookieExtractionTest` | Unit | ✅ |
| **S-004-69** | URL-encoded cookie value | `adapter/CookieExtractionTest` | Unit | ✅ |
| **S-004-70** | Wildcard vs health/admin priority | `proxy/EndpointPriorityTest` | Integration | ✅ |
| **S-004-71** | Request ID generated | `proxy/RequestIdTest` | Integration | ✅ |
| **S-004-72** | Request ID echoed | `proxy/RequestIdTest` | Integration | ✅ |
| **S-004-73** | Request ID in error response | `proxy/RequestIdTest` | Integration | ✅ |
| **S-004-74** | Query param binding in JSLT | `adapter/QueryParamExtractionTest` | Unit | ✅ |
| **S-004-75** | No query string → empty map | `adapter/QueryParamExtractionTest` | Unit | ✅ |
| **S-004-76** | Multi-value query param | `adapter/QueryParamExtractionTest` | Unit | ✅ |
| **S-004-77** | URL-encoded query param | `adapter/QueryParamExtractionTest` | Unit | ✅ |

---

## Category Summary

| Category | Scenarios | Automated | Manual/NFR | Coverage |
|----------|-----------|-----------|------------|----------|
| 1. Passthrough | S-004-01..06 | 6 | 0 | 100% |
| 2. Request Transformation | S-004-07..10 | 4 | 0 | 100% |
| 3. Response Transformation | S-004-11..14 | 4 | 0 | 100% |
| 4. Bidirectional | S-004-15..17 | 3 | 0 | 100% |
| 5. Error Handling | S-004-18..23, 55, 56 | 8 | 0 | 100% |
| 6. Configuration | S-004-24..28 | 5 | 0 | 100% |
| 7. Hot Reload | S-004-29..33 | 5 | 0 | 100% |
| 8. Health & Readiness | S-004-34..38, 70 | 6 | 0 | 100% |
| 9. TLS | S-004-39..43 | 5 | 0 | 100% |
| 10. Startup & Shutdown | S-004-44..47 | 4 | 0 | 100% |
| 11. Docker & Kubernetes | S-004-48..51 | 0 | 4 | 100% |
| 12. Upstream Protocol | S-004-52..54 | 3 | 0 | 100% |
| 13. Forwarded Headers | S-004-57..59 | 3 | 0 | 100% |
| 14. Dispatch Table | S-004-60..64 | 5 | 0 | 100% |
| 15. Concurrency | S-004-65..66 | 1 | 1 | 100% |
| 16. Cookie Binding | S-004-67..69 | 3 | 0 | 100% |
| 17. Request ID | S-004-71..73 | 3 | 0 | 100% |
| 18. Query Param Binding | S-004-74..77 | 4 | 0 | 100% |
| **Total** | **77** | **72** | **5** | **100%** |

---

## Test Class Summary

| Test Class | Location | Scenarios | Test Count |
|------------|----------|-----------|------------|
| `ProxyHandlerPassthroughTest` | `proxy/` | S-004-01..06 | 14 |
| `RequestTransformTest` | `proxy/` | S-004-07..10 | 4 |
| `ResponseTransformTest` | `proxy/` | S-004-11..13, 54 | 4 |
| `BidirectionalProxyTest` | `proxy/` | S-004-15..17 | 3 |
| `Rfc9457ErrorTest` | `proxy/` | S-004-09, 14 | 2 |
| `BackendErrorTest` | `proxy/` | S-004-18..20 | 3 |
| `UpstreamErrorTest` | `proxy/` | S-004-18..20 | 3 |
| `NonJsonBodyTest` | `proxy/` | S-004-21, 55 | 4 |
| `RequestBodySizeTest` | `proxy/` | S-004-22 | 2 |
| `ResponseBodySizeTest` | `proxy/` | S-004-56 | 2 |
| `HttpMethodTest` | `proxy/` | S-004-23 | 8 |
| `ConfigLoaderTest` | `config/` | S-004-24, 26, 28 | 6 |
| `EnvVarOverlayTest` | `config/` | S-004-25 | 41 |
| `ConfigValidationTest` | `config/` | S-004-27 | 15 |
| `ProxyConfigTest` | `config/` | — | 4 |
| `FileWatcherTest` | `proxy/` | S-004-29, 32 | 6 |
| `AdminReloadTest` | `proxy/` | S-004-30, 31 | 4 |
| `HotReloadIntegrationTest` | `proxy/` | S-004-29, 31 | 2 |
| `ZeroDowntimeReloadTest` | `proxy/` | S-004-33, 66 | 2 |
| `HealthEndpointTest` | `proxy/` | S-004-34 | 3 |
| `ReadinessEndpointTest` | `proxy/` | S-004-35..37 | 4 |
| `EndpointPriorityTest` | `proxy/` | S-004-38, 70 | 5 |
| `InboundTlsTest` | `proxy/` | S-004-39 | 3 |
| `InboundMtlsTest` | `proxy/` | S-004-40 | 4 |
| `OutboundTlsTest` | `proxy/` | S-004-41 | 3 |
| `OutboundMtlsTest` | `proxy/` | S-004-42 | 2 |
| `TlsConfigValidationTest` | `proxy/` | S-004-43 | 7 |
| `StartupSequenceTest` | `proxy/` | S-004-44 | 1 |
| `StartupFailureTest` | `proxy/` | S-004-45 | 7 |
| `GracefulShutdownTest` | `proxy/` | S-004-46, 47 | 3 |
| `UpstreamClientTest` | `proxy/` | S-004-52 | 10 |
| `ContentLengthTest` | `proxy/` | S-004-53 | 3 |
| `ForwardedHeadersTest` | `proxy/` | S-004-57..59 | 3 |
| `DispatchTableTest` | `proxy/` | S-004-60..64, 14 | 6 |
| `ConnectionPoolConfigTest` | `proxy/` | — | 2 |
| `CookieExtractionTest` | `adapter/` | S-004-67..69 | 5 |
| `QueryParamExtractionTest` | `adapter/` | S-004-74..77 | 7 |
| `StandaloneAdapterTest` | `adapter/` | S-004-64 | 8 |
| `RequestIdTest` | `proxy/` | S-004-71..73 | 3 |
| `StandaloneDependencyTest` | — | — | 1 |

---

## Test Fixtures

### Spec Files (`test-specs/`)

| File | Description | Scenarios |
|------|-------------|-----------|
| `identity-transform.yaml` | Body passthrough (`.`) | S-004-38, 70 |
| `request-body-transform.yaml` | Rename/restructure request body | S-004-07, 60 |
| `request-header-transform.yaml` | Add/remove request headers | S-004-08 |
| `response-body-transform.yaml` | Wrap response in envelope | S-004-11, 61 |
| `response-header-transform.yaml` | Add/remove response headers | S-004-12 |
| `response-status-override.yaml` | Override status 200→201 | S-004-13 |
| `bad-transform.yaml` | Intentionally broken JSLT | S-004-09, 14, 62 |

### Profile Files (`test-profiles/`)

| File | Description | Scenarios |
|------|-------------|-----------|
| `request-transform-profile.yaml` | Request transform routes | S-004-07, 08 |
| `response-transform-profile.yaml` | Response transform routes | S-004-11, 12, 13 |
| `bidirectional-profile.yaml` | Bidi routes | S-004-15, 16, 17 |
| `dispatch-table-profile.yaml` | Dispatch table routes | S-004-60..64 |
| `rfc9457-error-profile.yaml` | Error transform routes | S-004-09, 14 |
| `non-json-body-profile.yaml` | Non-JSON rejection routes | S-004-21, 55 |
| `request-id-error-profile.yaml` | Error route for X-Request-ID | S-004-73 |
| `wildcard-profile.yaml` | Wildcard `/*` matching | S-004-70 |

### Configuration Files (`config/`)

| File | Description | Scenarios |
|------|-------------|-----------|
| `full-config.yaml` | All settings exercised | S-004-24, 25, 28 |
| `minimal-config.yaml` | Minimal valid config | S-004-28 |
| `tls-config.yaml` | TLS configuration | S-004-39..42 |

### TLS Files (`tls/`)

| File | Description | Scenarios |
|------|-------------|-----------|
| `ca.p12` | CA keystore | S-004-39..42 |
| `server.p12` | Server keystore | S-004-39, 40 |
| `client.p12` | Client keystore | S-004-40, 42 |
| `truststore.p12` | CA truststore | S-004-41, 42 |

---

## NFR Coverage

| NFR | Description | Test / Evidence |
|-----|-------------|-----------------|
| NFR-004-01 | Passthrough overhead < 5 ms p99 | T-004-58 benchmark |
| NFR-004-02 | Startup < 2s | `StartupSequenceTest` |
| NFR-004-03 | Zero-downtime reload | `ZeroDowntimeReloadTest` |
| NFR-004-04 | Docker image < 150 MB | T-004-53 (80.8 MB) |
| NFR-004-05 | 1000 concurrent connections | T-004-58 benchmark |

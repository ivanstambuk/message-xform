# Feature 004 – Scenarios

| Field | Value |
|-------|-------|
| Status | Draft |
| Last updated | 2026-02-08 |
| Linked spec | `docs/architecture/features/004/spec.md` |

> Guardrail: Each scenario is a **testable contract**.

## Overview

This document lists the scenarios that validate Feature 004 (Standalone HTTP Proxy Mode).
Full definitions including input/output details are currently maintained in the spec
file during the draft phase.

## Scenario List

### Category 1: Basic Proxy
| ID | Name | Description |
|----|------|-------------|
| S-004-01 | Passthrough GET | Default passthrough behavior for GET requests |
| S-004-02 | Passthrough POST | Default passthrough behavior for POST with body |
| S-004-03 | Method Forwarding | Verify all HTTP methods (GET, POST, etc.) are forwarded |
| S-004-04 | Header Forwarding | Verify headers are forwarded and hop-by-hop stripped |
| S-004-05 | Query String Forwarding | Verify query parameters are preserved |
| S-004-06 | Path Forwarding | Verify full path structure is preserved |

### Category 2: Request Transformation
| ID | Name | Description |
|----|------|-------------|
| S-004-07 | Request Body Transform | JSLT transform applied to request body |
| S-004-08 | Request Header Transform | Headers added/removed/renamed in request |
| S-004-09 | Request Transform Error | Transform failure prevents forwarding, returns error |
| S-004-10 | Request No Body | Transform handles null/empty body gracefully |

### Category 3: Response Transformation
| ID | Name | Description |
|----|------|-------------|
| S-004-11 | Response Body Transform | JSLT transform applied to response body |
| S-004-12 | Response Header Transform | Headers added/removed/renamed in response |
| S-004-13 | Status Code Override | Response status modified by transform |
| S-004-14 | Response Transform Error | Transform failure returns error response |

### Category 4: Bidirectional Transformation
| ID | Name | Description |
|----|------|-------------|
| S-004-15 | Full Bidirectional | Request AND response transformed in one flow |
| S-004-16 | Eq/Res Pattern | Request transformed, response passed through |
| S-004-17 | Pth/Res Pattern | Request passed through, response transformed |

### Category 5: Error Handling
| ID | Name | Description |
|----|------|-------------|
| S-004-18 | Backend Unreachable | 502 Bad Gateway on connection failure |
| S-004-19 | Backend Timeout | 504 Gateway Timeout on read timeout |
| S-004-20 | Connection Refused | 502 Bad Gateway on port closed |
| S-004-21 | Malformed JSON | 400 Bad Request on invalid JSON body |
| S-004-22 | Body Too Large | 413 Payload Too Large on size limit exceeded |
| S-004-23 | Unknown Method | 405 Method Not Allowed |

### Category 6: Configuration
| ID | Name | Description |
|----|------|-------------|
| S-004-24 | YAML Config Load | Config file loaded correctly at startup |
| S-004-25 | Env Var Override | Environment variables override YAML values |
| S-004-26 | Missing Config | Error and exit if config missing |
| S-004-27 | Invalid Config | Error and exit if config invalid |
| S-004-28 | Default Values | Verify defaults used for optional fields |

### Category 7: Hot Reload
| ID | Name | Description |
|----|------|-------------|
| S-004-29 | File Watcher Reload | Reload triggered by file change |
| S-004-30 | Admin API Reload | Reload triggered by POST /admin/reload |
| S-004-31 | Reload Failure | Invalid spec change rejected, old state preserved |
| S-004-32 | Debounce | Rapid changes trigger single reload |
| S-004-33 | Zero Downtime | In-flight requests complete during reload |

### Category 8: Health & Readiness
| ID | Name | Description |
|----|------|-------------|
| S-004-34 | Liveness UP | /health returns 200 UP |
| S-004-35 | Readiness READY | /ready returns 200 READY |
| S-004-36 | Readiness STARTING | /ready returns 503 before engine load |
| S-004-37 | Readiness UNREACHABLE | /ready returns 503 if backend down |
| S-004-38 | Probe Exclusion | Probes not subject to transform matching |

### Category 9: TLS
| ID | Name | Description |
|----|------|-------------|
| S-004-39 | Inbound TLS | Proxy serves HTTPS |
| S-004-40 | Inbound mTLS | Client cert authentication required |
| S-004-41 | Outbound TLS | Proxy validates backend cert |
| S-004-42 | Outbound mTLS | Proxy presents client cert to backend |
| S-004-43 | TLS Config Error | Startup failure on invalid keystore |

### Category 10: Startup & Shutdown
| ID | Name | Description |
|----|------|-------------|
| S-004-44 | Clean Startup | Startup sequence and logging |
| S-004-45 | Startup Failure | Exit on load failure |
| S-004-46 | Graceful Shutdown | Drain in-flight requests on SIGTERM |
| S-004-47 | Shutdown Completion | Long request completes before exit |

### Category 11: Docker & Kubernetes
| ID | Name | Description |
|----|------|-------------|
| S-004-48 | Docker Build | Image creation size/success |
| S-004-49 | Docker Run | Container startup with env vars |
| S-004-50 | Volume Mount | Specs loaded from mounted volume |
| S-004-51 | Shadow JAR | Executable JAR standalone run |

## Coverage Matrix

| Requirement | Validating Scenarios |
|-------------|----------------------|
| FR-004-01 (Proxy Core) | S-004-01, S-004-02, S-004-03 |
| FR-004-02 (Req Transform) | S-004-07, S-004-08, S-004-15 |
| FR-004-03 (Res Transform) | S-004-11, S-004-12, S-004-13 |
| FR-004-04 (Header Fwd) | S-004-04 |
| FR-004-05 (Methods) | S-004-03, S-004-23 |
| FR-004-10 (Config) | S-004-24, S-004-26, S-004-27 |
| FR-004-11 (Env Vars) | S-004-25, S-004-49 |
| FR-004-13 (Body Limit) | S-004-22 |
| FR-004-14..17 (TLS) | S-004-39, S-004-40, S-004-41, S-004-42 |
| FR-004-19 (Reload) | S-004-29, S-004-30 |
| FR-004-21..22 (Probes) | S-004-34..38 |
| FR-004-23..26 (Errors) | S-004-18..21 |

# Feature 003 – PingGateway Adapter

| Field | Value |
|-------|-------|
| Status | 🔬 Research Complete |
| Last updated | 2026-02-16 |
| Owners | Ivan |
| Linked plan | `docs/architecture/features/003/plan.md` (TBD) |
| Linked tasks | `docs/architecture/features/003/tasks.md` (TBD) |
| Roadmap entry | #3 – PingGateway Adapter |
| Depends on | Feature 001 (core engine) |

> Guardrail: This specification is the single normative source of truth for the feature.
> Track questions in `docs/architecture/open-questions.md`, encode resolved answers
> here, and use ADRs under `docs/decisions/` for architectural clarifications.

## Key Research

- PingGateway reference: `docs/reference/vendor/pinggateway-2025.11.txt` (30,588 lines)
- PingGateway SDK guide: `docs/reference/pinggateway-sdk-guide.md` (COMPLETE)
- PingGateway OOTB capability: `docs/research/pinggateway-ootb-transformation-capabilities.md` (COMPLETE)
- Gateway Candidates: `docs/research/gateway-candidates.md` (evaluation complete)

## Overview

Gateway adapter that integrates message-xform with **PingGateway**
(formerly ForgeRock Identity Gateway / IG, version 2025.11). Implements the
CHF `Filter` interface to intercept request/response flows through PingGateway
routes and apply JSLT-based message transformations using the core engine.

The adapter is a **thin bridge layer** — all transformation logic lives in
`message-xform-core`. The adapter's sole responsibility is:

1. Reading native CHF `Request`/`Response` data (body, headers, status, URL).
2. Wrapping it into a core `Message` via `GatewayAdapter` SPI.
3. Delegating transformation to `TransformEngine`.
4. Writing transformed results back to the native objects.

**Affected modules:** New module `adapter-pinggateway`.

## SDK API Surface

The adapter uses the PingGateway Common HTTP Framework (CHF) types:

| CHF Type | Role | Package |
|----------|------|---------|
| `Filter` | Intercept request/response chain | `org.forgerock.http.Filter` |
| `Handler` | Next handler in chain (called to forward) | `org.forgerock.http.Handler` |
| `Request` | HTTP request — URI, method, headers, entity | `org.forgerock.http.protocol.Request` |
| `Response` | HTTP response — status, headers, entity | `org.forgerock.http.protocol.Response` |
| `Entity` | Body content — `getBytes()`/`setBytes()` | `org.forgerock.http.protocol.Entity` |
| `Headers` | Case-insensitive header map | `org.forgerock.http.protocol.Headers` |
| `Status` | HTTP status code + reason | `org.forgerock.http.protocol.Status` |
| `Context` | Hierarchical context chain | `org.forgerock.services.context.Context` |
| `SessionContext` | Session key-value store | `org.forgerock.http.session.SessionContext` |
| `AttributesContext` | Route-level computed attributes | `org.forgerock.services.context.AttributesContext` |
| `GenericHeaplet` | Configuration factory base class | `org.forgerock.openig.heap.GenericHeaplet` |
| `ClassAliasResolver` | Short name → FQCN mapping | `org.forgerock.openig.alias.ClassAliasResolver` |
| `Promise` | Non-blocking async result | `org.forgerock.util.promise.Promise` |

**Key differences from PingAccess:**
- Single `filter()` method (no separate `handleRequest`/`handleResponse`)
- Response accessed via promise chain (`next.handle().thenOnResult()`)
- Body via `Entity.getBytes()`/`setBytes()` (no `Body.read()` step)
- Headers are case-insensitive natively
- No admin UI — configuration via JSON route files + `Heaplet`
- No `Exchange` — `Request` and `Response` are separate objects

## Implementation Language

**Java 21** — PingGateway 2025.11 supports Java 17+. Same toolchain as core.

## Goals

- G-003-01 – Implement `GatewayAdapter<PingGatewayExchange>` for PingGateway,
  bridging CHF `Request`/`Response`/`Entity`/`Headers` to the core engine's
  `Message` record via a `PingGatewayExchange` wrapper.
- G-003-02 – Implement `Filter` interface (`MessageTransformFilter`) with
  request-phase transformation in `filter()` body and response-phase
  transformation in `thenOnResult()` callback.
- G-003-03 – Provide Heaplet-based configuration for spec directory, profile
  directory, error mode, reload interval, and schema validation.
- G-003-04 – Package as a shadow JAR for PingGateway's `$HOME/.openig/extra/`
  directory with correct `META-INF/services` registration.
- G-003-05 – Support Groovy scripted filter as alternative deployment option.

## Non-Goals

- N-003-01 – PingGateway admin UI integration (Studio). Custom filters cannot
  be configured through PingGateway Studio — JSON route config only.
- N-003-02 – PingGateway Cloud deployment (PG as-a-Service). This adapter
  targets self-hosted PingGateway installations.
- N-003-03 – SAML/federation transformations. The adapter transforms HTTP
  message payloads, not SAML assertions or federation metadata.

---

## Functional Requirements (Draft)

### FR-003-01: GatewayAdapter Implementation

**Requirement:** The module MUST provide a class `PingGatewayAdapter`
implementing `GatewayAdapter<PingGatewayExchange>` with three methods:

1. `wrapRequest(PingGatewayExchange)` → `Message`
2. `wrapResponse(PingGatewayExchange)` → `Message`
3. `applyChanges(Message, PingGatewayExchange)`

Follows the copy-on-wrap semantics (ADR-0013) established by the PA adapter.

#### wrapRequest Mapping

| Message Field | Source |
|---------------|--------|
| `body` | `request.getEntity().getBytes()` → validate JSON → `MessageBody.json(bytes)` or `MessageBody.empty()` |
| `headers` | `request.getHeaders().copyAsMultiMapOfStrings()` → lowercase keys → `HttpHeaders.ofMulti(map)` |
| `statusCode` | `null` (requests have no status code) |
| `requestPath` | `request.getUri().getPath()` |
| `requestMethod` | `request.getMethod()` → uppercase |
| `queryString` | `request.getUri().getQuery()` |
| `session` | See FR-003-06 |

#### wrapResponse Mapping

| Message Field | Source |
|---------------|--------|
| `body` | `response.getEntity().getBytes()` → validate JSON → `MessageBody.json(bytes)` or `MessageBody.empty()` |
| `headers` | `response.getHeaders().copyAsMultiMapOfStrings()` → lowercase keys → `HttpHeaders.ofMulti(map)` |
| `statusCode` | `response.getStatus().getCode()` |
| `requestPath` | `request.getUri().getPath()` (original request for profile matching) |
| `requestMethod` | `request.getMethod()` |
| `queryString` | `request.getUri().getQuery()` |
| `session` | See FR-003-06 |

### FR-003-02: Filter Implementation

**Requirement:** The module MUST provide `MessageTransformFilter` implementing
`org.forgerock.http.Filter`. The single `filter()` method handles both
request and response phases:

- **Request phase:** Transform `request` before calling `next.handle()`.
- **Response phase:** Transform `response` in `thenOnResult()` callback.

Non-blocking mandate: MUST NOT use `get()` or `getOrThrow()` on promises.

### FR-003-03: Heaplet Configuration

**Requirement:** The filter MUST provide a nested `Heaplet` class extending
`GenericHeaplet` with `create()` and `destroy()` methods.

Configuration fields (read from route JSON):

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `specsDir` | String | Yes | — | Path to transform spec YAML directory |
| `profilesDir` | String | No | `/profiles` | Path to profile directory |
| `activeProfile` | String | No | (empty) | Profile name to activate |
| `errorMode` | String | No | `PASS_THROUGH` | `PASS_THROUGH` or `DENY` |
| `reloadIntervalSec` | Integer | No | `0` | Spec reload interval (0 = disabled) |
| `schemaValidation` | String | No | `LENIENT` | `STRICT` or `LENIENT` |

### FR-003-04: Shadow JAR Packaging

**Requirement:** The module MUST produce a shadow JAR that bundles
`message-xform-core` and its transitive dependencies, excluding
PingGateway-provided classes (Jackson, SLF4J, CHF).

### FR-003-05: ClassAliasResolver

**Requirement:** The module MUST provide a `ClassAliasResolver` registered
via `META-INF/services/` that maps `"MessageTransformFilter"` to the FQCN.

### FR-003-06: Session Context Binding

**Requirement:** The adapter MUST populate the `session` field of the `Message`
record from PingGateway's context chain. Merge layers (ascending precedence):

| Layer | Source | Contents | Precedence |
|-------|--------|----------|------------|
| 1 (base) | `SessionContext.getSession()` | Session key-value pairs | Lowest |
| 2 | `AttributesContext.getAttributes()` | Route-level attributes | |
| 3 (top) | `OAuth2Context` (if present) | Access token, scopes, expiry | Highest |

If no session context exists: `SessionContext.empty()`.

### FR-003-07: Error Mode

**Requirement:** Same error mode semantics as PingAccess adapter (FR-002-11):

- **PASS_THROUGH:** Log warning, preserve original message, continue chain.
- **DENY:** On request error → return error `Response` directly (do not call
  `next.handle()`). On response error → replace response with RFC 9457 error.

---

## Scenarios (Draft)

> Full scenario list to be elaborated in the next session.

| ID | Title | Direction | Status |
|----|-------|-----------|--------|
| S-003-01 | Request body transformation | REQUEST | 🔲 |
| S-003-02 | Response body transformation | RESPONSE | 🔲 |
| S-003-03 | Header add/remove/rename | BOTH | 🔲 |
| S-003-04 | Profile-based spec matching | REQUEST | 🔲 |
| S-003-05 | No profile match → passthrough | REQUEST | 🔲 |
| S-003-06 | PASS_THROUGH on transform error | REQUEST | 🔲 |
| S-003-07 | DENY on request transform error | REQUEST | 🔲 |
| S-003-08 | DENY on response transform error | RESPONSE | 🔲 |
| S-003-09 | Non-JSON body → body skip, headers apply | REQUEST | 🔲 |
| S-003-10 | Spec hot-reload via reloadIntervalSec | N/A | 🔲 |
| S-003-11 | Session context ($session) in JSLT | REQUEST | 🔲 |
| S-003-12 | Query param / cookie access in JSLT | REQUEST | 🔲 |
| S-003-13 | Status code transformation | RESPONSE | 🔲 |
| S-003-14 | URI path rewrite | REQUEST | 🔲 |

---

## Status

🔬 Research complete. Spec draft in progress. Next: elaborate scenarios, then
plan/tasks.

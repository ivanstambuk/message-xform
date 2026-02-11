# Feature 002 — Spec Gap Analysis (PingAccess SDK Deep Dive)

| Field | Value |
|-------|-------|
| Created | 2026-02-11 |
| Status | ✅ Complete |
| Source | SDK deep dive session — decompiled SDK classes + sample code analysis |
| Method | Systematic comparison of spec.md (953 lines) against actual SDK bytecode (166 classes) and 9 sample rules |

> **Purpose:** Identify gaps, inaccuracies, and missing coverage in the Feature 002
> spec by comparing it against ground truth from the PingAccess 9.0.1 SDK.

---

## Batch Execution Plan

| Batch | Theme | Gaps | Status |
|-------|-------|------|--------|
| B1 | Identity & Session Context Enrichment | GAP-01, GAP-02, GAP-03 | ✅ `ea6e32e` |
| B2 | SDK API Surface Corrections | GAP-04, GAP-05, GAP-06 | ✅ `3eca06e` |
| B3 | Configuration & UI Patterns | GAP-07, GAP-08 | ✅ `5fc2a8b` |
| B4 | Error Handling & Response Patterns | GAP-09, GAP-10 | ✅ `78f3201` |
| B5 | Testing Strategy & Patterns | GAP-11, GAP-12 | ✅ `a1c276d` |
| B6 | Non-Functional & Deployment | GAP-13, GAP-14, GAP-15 | ✅ `3bbffec` |

---

## Gap Registry

### GAP-01: Identity interface missing `getSessionStateSupport()` and `setMappedSubject()`

- **Status:** ✅ RESOLVED in B1 (`ea6e32e`)
- **Severity:** 🔴 High
- **Category:** Identity & Session Context
- **What was missing:** `Identity` section only listed 6 methods; missing `getSessionStateSupport()`, `getOAuthTokenMetadata()`, `setMappedSubject()`
- **What was fixed:** Added all methods to Identity section. Added full `SessionStateSupport` and `OAuthTokenMetadata` API sections to SDK API Surface.

### GAP-02: SessionStateSupport not covered anywhere in spec

- **Status:** ✅ RESOLVED in B1 (`ea6e32e`)
- **Severity:** 🔴 High
- **Category:** Identity & Session Context
- **What was missing:** `SessionStateSupport` interface not mentioned in spec. Provides persistent session key-value store (read/write `Map<String, JsonNode>`).
- **What was fixed:** Added `SessionStateSupport` section to SDK API Surface. FR-002-06 expanded to include `$session.sessionState` with read-only snapshot. Adapter usage note added.

### GAP-03: OAuthTokenMetadata not surfaced to `$session`

- **Status:** ✅ RESOLVED in B1 (`ea6e32e`)
- **Severity:** 🟡 Medium
- **Category:** Identity & Session Context
- **What was missing:** `$session` only had identity fields; no OAuth client context (clientId, scopes, tokenType, realm).
- **What was fixed:** Added `$session.oauth.*` sub-object with full schema table. Added OAuth metadata code example and null-handling.

### GAP-04: Exchange missing `getPolicyConfiguration()`, `getTargetHosts()`, `getResolvedLocale()`

- **Status:** ✅ RESOLVED in B2 (`3eca06e`)
- **Severity:** 🟡 Medium
- **Category:** SDK API Surface
- **What was missing:** Exchange section missing 3 methods and PolicyConfiguration sub-interface.
- **What was fixed:** Added all methods to Exchange section. Added `PolicyConfiguration` → `Application` / `Resource` sub-interfaces with method list. Design note added for future `$context` enrichment.

### GAP-05: ConfigurationType enum values mismatch

- **Status:** ✅ RESOLVED in B2 (`3eca06e`)
- **Severity:** 🟢 Low
- **Category:** SDK API Surface
- **What was missing:** Spec listed `AUTOCOMPLETECLOSED`, `COMPOSITE`, `RADIO_BUTTON` which don't exist in SDK 9.0.1 bytecode.
- **What was fixed:** Corrected enum to match bytecode. Added note explaining radio buttons use boolean fields, not a `ConfigurationType`.

### GAP-06: `RuleInterceptorBase` vs `AsyncRuleInterceptorBase` method naming

- **Status:** ✅ RESOLVED in B2 (`3eca06e`)
- **Severity:** 🟢 Low
- **Category:** SDK API Surface
- **What was missing:** No note about `getRenderer()` (sync) vs `getTemplateRenderer()` (async) naming difference.
- **What was fixed:** Added clarifying note after Plugin Configuration section.

### GAP-07: `@UIElement` advanced patterns not documented

- **Status:** ✅ RESOLVED in B3 (`5fc2a8b`)
- **Severity:** 🟡 Medium
- **Category:** Configuration & UI
- **What was missing:** Only basic `@UIElement` attributes documented. Missing `modelAccessor`, `helpContent`, `helpTitle`, `helpURL`, `subFields`, JSR-380 validation.
- **What was fixed:** Created Appendix D with full SDK sample code examples covering annotation-driven vs programmatic `ConfigurationBuilder`, TABLE sub-fields, SELECT options, help text, and Bean Validation (`@Min`, `@Max`, `@NotNull`, `@Valid`, `@JsonUnwrapped`).

### GAP-08: `ConfigurationBuilder.from()` pattern not documented

- **Status:** ✅ RESOLVED in B3 (`5fc2a8b`)
- **Severity:** 🟢 Low
- **Category:** Configuration & UI
- **What was missing:** Two config field discovery patterns exist (annotation-driven, programmatic); neither documented.
- **What was fixed:** Both patterns documented in Appendix D with code samples from SDK rules. `ErrorHandlerUtil` composition pattern included. FR-002-04 updated with config discovery note.

### GAP-09: DENY mode in `handleRequest` — Response is null (NPE)

- **Status:** ✅ RESOLVED in B4 (`78f3201`) — **CRITICAL FIX**
- **Severity:** 🔴 High
- **Category:** Error Handling
- **What was wrong:** Spec described calling `exchange.getResponse().setBodyContent()` during `handleRequest()`. `getResponse()` is **null** during request phase → `NullPointerException` at runtime.
- **What was fixed:** Replaced with `ResponseBuilder.status(502).body(errorBody).build()` + `exchange.setResponse(response)` pattern (from SDK's `Clobber404ResponseRule`). Added full `ResponseBuilder` API documentation. Response-phase handling clarified as in-place rewrite (safe because response exists).

### GAP-10: `ResponseBuilder` and error response patterns not documented

- **Status:** ✅ RESOLVED in B4 (`78f3201`)
- **Severity:** 🟡 Medium
- **Category:** Error Handling & Response
- **What was missing:** `ResponseBuilder` API not in spec. Error response construction pattern not documented.
- **What was fixed:** Added `ResponseBuilder` API (`ok()`, `notFound()`, `status(int)`, `header()`, `body()`, `build()`). Added to Supporting Types. SDK references to `Clobber404ResponseRule` and `RiskAuthorizationRule` patterns.

### GAP-11: Test patterns missing SDK-grounded mock setup

- **Status:** ✅ RESOLVED in B5 (`a1c276d`)
- **Severity:** 🟡 Medium
- **Category:** Testing Strategy
- **What was missing:** Test strategy had no concrete mock patterns. No guidance on config validation testing.
- **What was fixed:** Added full Mockito mock chain from `ExternalAuthorizationRuleTest` (Exchange→Request→Body→Headers→Identity→SessionStateSupport→OAuthTokenMetadata). Added standalone `Validator` pattern for config tests. Added `ArgumentCaptor` pattern for DENY mode verification.

### GAP-12: Test dependency versions not specified

- **Status:** ✅ RESOLVED in B5 (`a1c276d`)
- **Severity:** 🟢 Low
- **Category:** Testing Strategy
- **What was missing:** No alignment table with SDK sample dependency versions.
- **What was fixed:** Added dependency alignment table (JUnit 5 deviation noted as intentional, Mockito 5.x, Hibernate Validator needed for config tests, Spring Test skipped).

### GAP-13: Thread safety NFR too strict

- **Status:** ✅ RESOLVED in B6 (`3bbffec`)
- **Severity:** 🟡 Medium
- **Category:** Non-Functional
- **What was wrong:** Spec said "all state must be method-local or immutable" which is too strict — `@Inject` fields and `configuration` are instance-level but set-once.
- **What was fixed:** Refined NFR-002-03 and Constraint #4 to distinguish init-time fields (`@Inject`, `configuration`) from per-request state. Init-time fields are safe for concurrent reads.

### GAP-14: Jackson relocation was optional — should be mandatory

- **Status:** ✅ RESOLVED in B6 (`3bbffec`)
- **Severity:** 🟡 Medium
- **Category:** Deployment
- **What was wrong:** Spec said relocation "SHOULD" be used, "can be skipped if version alignment is confirmed". This is unsafe.
- **What was fixed:** Changed to **MUST**. Documented the `ClassCastException` risk (PA's `JsonNode` vs adapter's `JsonNode` from different classloaders). Added boundary conversion pattern via serialization in `buildSessionContext()`.

### GAP-15: `ServiceFactory` utility not documented

- **Status:** ✅ RESOLVED in B6 (`3bbffec`)
- **Severity:** 🟢 Low
- **Category:** SDK API Surface
- **What was missing:** `ServiceFactory` (SPI discovery, factory methods) not in spec. Useful for testing.
- **What was fixed:** Added to Supporting Types section with note about testing utility.

---

## Summary

| Severity | Count | All Resolved? |
|----------|-------|---------------|
| 🔴 High | 3 (GAP-01, GAP-02, GAP-09) | ✅ Yes |
| 🟡 Medium | 6 (GAP-03, GAP-04, GAP-07, GAP-11, GAP-13, GAP-14) | ✅ Yes |
| 🟢 Low | 6 (GAP-05, GAP-06, GAP-08, GAP-10, GAP-12, GAP-15) | ✅ Yes |
| **Total** | **15** | **✅ All resolved** |

### Post-Analysis Discoveries

Additional inaccuracies found after initial gap analysis (during flat `$session` redesign):

| ID | Issue | Status |
|----|-------|--------|
| GAP-16 | `Identity.getTokenExpiration()` returns `Instant`, not `Date` (spec said `Date`) | ⬜ Pending |
| GAP-17 | `OAuthTokenMetadata` missing `getExpiresAt()` and `getRetrievedAt()` | ⬜ Pending |
| GAP-18 | `SessionStateSupport` missing `getAttributeNames()` | ⬜ Pending |
| GAP-19 | `$session` should be flat hierarchy (merge identity + OAuth + claims + session state) | ⬜ Pending |

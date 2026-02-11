# Feature 002 — Spec Gap Analysis (PingAccess SDK Deep Dive)

| Field | Value |
|-------|-------|
| Created | 2026-02-11 |
| Status | 🔴 In Progress |
| Source | SDK deep dive session — decompiled SDK classes + sample code analysis |
| Method | Systematic comparison of spec.md (953 lines) against actual SDK bytecode (166 classes) and 9 sample rules |

> **Purpose:** Identify gaps, inaccuracies, and missing coverage in the Feature 002
> spec by comparing it against ground truth from the PingAccess 9.0.1 SDK.

---

## Batch Execution Plan

Gaps are grouped into themed batches for incremental spec updates.
Each batch is a single commit touching related spec sections.

| Batch | Theme | Gaps | Status |
|-------|-------|------|--------|
| B1 | Identity & Session Context Enrichment | GAP-01, GAP-02, GAP-03 | ⬜ Pending |
| B2 | SDK API Surface Corrections | GAP-04, GAP-05, GAP-06 | ⬜ Pending |
| B3 | Configuration & UI Patterns | GAP-07, GAP-08 | ⬜ Pending |
| B4 | Error Handling & Response Patterns | GAP-09, GAP-10 | ⬜ Pending |
| B5 | Testing Strategy & Patterns | GAP-11, GAP-12 | ⬜ Pending |
| B6 | Non-Functional & Deployment | GAP-13, GAP-14, GAP-15 | ⬜ Pending |

---

## Gap Registry

### GAP-01: Identity interface missing `getSessionStateSupport()` and `setMappedSubject()`

- **Severity:** 🔴 High
- **Category:** Identity & Session Context
- **Spec location:** Lines 176–185 (Identity section in SDK API Surface)
- **Current spec says:** `Identity` has `getSubject()`, `getMappedSubject()`, `getTrackingId()`, `getTokenId()`, `getTokenExpiration()`, `getAttributes()`
- **SDK reality:** `Identity` also has:
  - `getSessionStateSupport()` → `SessionStateSupport` — Allows read/write of persistent session attributes (key→`JsonNode`). This is how the `ExternalAuthorizationRule` sample caches authorization decisions.
  - `getOAuthTokenMetadata()` → `OAuthTokenMetadata` — Provides `getClientId()`, `getScopes()`, `getTokenType()`, `getRealm()`.
  - `setMappedSubject(String)` — Allows identity mapping plugins to set a mapped subject (used by `SampleJsonIdentityMapping`).
- **Impact:** FR-002-06 (Session Context Binding) is incomplete — it doesn't surface OAuth metadata or session state to `$session`.
- **Resolution:** Update spec Identity section to include all methods. Expand FR-002-06 to add `oauthMetadata` and optionally `sessionState` to the `$session` JsonNode.

### GAP-02: SessionStateSupport not covered anywhere in spec

- **Severity:** 🔴 High
- **Category:** Identity & Session Context
- **Spec location:** Not mentioned
- **SDK reality:** `SessionStateSupport` provides:
  - `getAttributes()` → `Map<String, JsonNode>`
  - `getAttribute(String)` → `JsonNode`
  - `getAttributeValue(String)` → `JsonNode`
  - `setAttribute(String, JsonNode)`
  - `removeAttribute(String)`
- **Impact:** The adapter could surface session state attributes to JSLT transforms, and/or store transform metadata in session state for cross-request persistence.
- **Resolution:** Add `SessionStateSupport` to SDK API Surface section. Add design decision on whether to expose session state in `$session.sessionState` (read-only) and whether to write transform results back to session state.

### GAP-03: OAuthTokenMetadata not surfaced to `$session`

- **Severity:** 🟡 Medium
- **Category:** Identity & Session Context
- **Spec location:** Lines 494–528 (FR-002-06)
- **Current spec says:** `$session` contains `subject`, `mappedSubject`, `trackingId`, `tokenId`, `tokenExpiration`, `attributes`
- **SDK reality:** `OAuthTokenMetadata` provides:
  - `getClientId()` → OAuth client ID
  - `getScopes()` → Set of granted scopes
  - `getTokenType()` → Token type string
  - `getRealm()` → Realm string
- **Impact:** JSLT transforms cannot access OAuth metadata (e.g., `$session.oauth.clientId` for scope-based transforms).
- **Resolution:** Add `oauth` sub-object to `$session` with `clientId`, `scopes`, `tokenType`, `realm`.

### GAP-04: Exchange missing `getPolicyConfiguration()`, `getTargetHosts()`, `getCreationTime()`

- **Severity:** 🟡 Medium
- **Category:** SDK API Surface
- **Spec location:** Lines 104–120 (Exchange section)
- **Current spec lists:** `getRequest`, `getResponse`, `getIdentity`, `getSslData`, `getOriginalRequestUri`, `getSourceIp`, `getUserAgentHost`, `getUserAgentProtocol`, `getTargetScheme`, `getCreationTime`, `getProperty/setProperty`
- **SDK reality:** Exchange also has:
  - `getPolicyConfiguration()` → `PolicyConfiguration` (with `getApplication()` and `getResource()`)
  - `getTargetHosts()` → `List<?>` (backend target hosts)
  - `getResolvedLocale()` → `Locale`
  - These are used by `ParameterRule` for host logging and by `RiskAuthorizationRule` for building request URLs.
- **Impact:** The spec lists `getCreationTime` (correct) but misses `getPolicyConfiguration()` and `getTargetHosts()` which could be useful for context-aware transforms. `Application.getName()` and `Resource.getName()` could inform routing.
- **Resolution:** Add missing Exchange methods to SDK API Surface. Add a design note on whether `$context.application` or `$context.resource` should be surfaced.

### GAP-05: ConfigurationType enum values mismatch

- **Severity:** 🟢 Low
- **Category:** SDK API Surface
- **Spec location:** Lines 218–220 (ConfigurationType enum)
- **Current spec says:** `TEXT, TEXTAREA, TIME, SELECT, GROOVY, CONCEALED, LIST, TABLE, CHECKBOX, AUTOCOMPLETEOPEN, AUTOCOMPLETECLOSED, COMPOSITE, RADIO_BUTTON`
- **SDK reality (from bytecode):** `TEXT, TEXTAREA, TIME, SELECT, GROOVY, CONCEALED, LIST, TABLE, CHECKBOX, AUTOCOMPLETEOPEN` — **no `AUTOCOMPLETECLOSED`, `COMPOSITE`, or `RADIO_BUTTON`** in the enum. Sample code uses `RADIO` annotation style (`radio1`, `radio2` fields as `boolean`), not an enum value.
- **Impact:** Minor — incorrect enum values could mislead during implementation. The `RADIO` UI pattern is achieved via boolean fields, not a dedicated `ConfigurationType`.
- **Resolution:** Correct the enum list. Add a note about the radio/boolean pattern.

### GAP-06: `RuleInterceptorBase` vs `AsyncRuleInterceptorBase` method differences

- **Severity:** 🟢 Low
- **Category:** SDK API Surface
- **Spec location:** Lines 88–102 (AsyncRuleInterceptorBase section)
- **Current spec says:** `getTemplateRenderer()` method name
- **SDK reality (from bytecode):** `AsyncRuleInterceptorBase` has `getTemplateRenderer()` ✓, but `RuleInterceptorBase` has `getRenderer()` (different name). The current spec correctly focuses on Async, but should note this for reference.
- **Impact:** Minimal — we use `AsyncRuleInterceptorBase`. But spec should be precise.
- **Resolution:** Add a note clarifying the naming difference between sync and async base classes.

### GAP-07: `@UIElement` advanced patterns not documented

- **Severity:** 🟡 Medium
- **Category:** Configuration & UI
- **Spec location:** Lines 438–468 (FR-002-04)
- **Current spec says:** Basic `@UIElement` annotation with `order`, `type`, `label`, `required`, `defaultValue`
- **SDK reality (from samples):** Additional important `@UIElement` attributes:
  - `modelAccessor` — references accessor classes (e.g., `ThirdPartyServiceAccessor.class`, `TrustedCertificateGroupAccessor.class`, `KeyPairAccessor.class`) for dynamic dropdown population
  - `helpContent`, `helpTitle`, `helpURL` — inline help text in admin UI
  - `options` (on ConfigurationBuilder) — defines SELECT dropdown options programmatically
  - `subFields` — defines TABLE sub-columns (e.g., `ParameterRule`'s table with `paramName`/`paramValue` sub-fields)
  - `@Valid` — JSR-380 nested validation
  - `@Min`, `@Max`, `@NotNull` — standard Bean Validation constraints
- **Impact:** The spec's configuration section is minimal. While our initial config is simple (TEXT + SELECT), documenting these patterns enables future config extensibility.
- **Resolution:** Add a "Configuration Patterns" appendix covering advanced `@UIElement` features observed in SDK samples.

### GAP-08: `ConfigurationBuilder.from()` pattern not documented

- **Severity:** 🟢 Low
- **Category:** Configuration & UI
- **Spec location:** Lines 200–221 (Plugin Configuration & UI section)
- **Current spec says:** Shows `@UIElement` annotation on fields
- **SDK reality:** Two config field discovery patterns exist:
  1. **Annotation-driven** (`ConfigurationBuilder.from(Config.class)`) — auto-discovers `@UIElement`-annotated fields (used by `Clobber404ResponseRule`, `RiskAuthorizationRule`)
  2. **Programmatic** (`new ConfigurationBuilder().configurationField(...)`) — manual field registration with options, help text, sub-fields (used by `ParameterRule`)
  - Both can compose: `ConfigurationBuilder.from(Config.class).addAll(ErrorHandlerUtil.getConfigurationFields())`
- **Impact:** The adapter will use pattern 1, but should document both for correctness.
- **Resolution:** Document both patterns and the ErrorHandlerUtil composition pattern.

### GAP-09: DENY mode in `handleRequest` — Response may not exist yet

- **Severity:** 🔴 High
- **Category:** Error Handling
- **Spec location:** Lines 641–647 (FR-002-11 DENY mode)
- **Current spec says:** "writes this error body to the exchange's response via `exchange.getResponse().setBodyContent(errorBytes)`"
- **SDK reality:** During `handleRequest()`, **`exchange.getResponse()` is null** — the backend hasn't responded yet. The spec describes writing to response during request phase, which would cause a `NullPointerException`.
- **Correct pattern (from SDK samples):**
  - `RiskAuthorizationRule.handleRequest()` returns `Outcome.RETURN` with `POLICY_ERROR_INFO` set — the `ErrorHandlingCallback` writes the error response
  - `WriteResponseRule` throws `AccessException` which triggers `getErrorHandlingCallback().writeErrorResponse(exchange)`
  - `Clobber404ResponseRule.handleResponse()` uses `ResponseBuilder.notFound()` + `exchange.setResponse(response)` to build a response from scratch
- **Impact:** Critical — the DENY request-phase implementation in the spec would fail at runtime. Must use `ResponseBuilder` to create a new response, or delegate to `ErrorHandlingCallback`.
- **Resolution:** Fix DENY mode request-phase handling: either (a) use `ResponseBuilder` + `exchange.setResponse()`, or (b) set `POLICY_ERROR_INFO` + return `Outcome.RETURN` to let ErrorHandlingCallback write the response, or (c) throw `AccessException` to delegate to ErrorHandlingCallback.

### GAP-10: `ResponseBuilder` and `TemplateRenderer` not documented

- **Severity:** 🟡 Medium
- **Category:** Error Handling & Response
- **Spec location:** Not mentioned
- **SDK reality:**
  - `ResponseBuilder` — factory for creating `Response` objects: `.ok()`, `.notFound()`, `.status(int)`, `.header(name, value)`, `.body(bytes)`, `.build()`
  - `TemplateRenderer` — renders error pages from templates. Available via `getTemplateRenderer()` on `AsyncRuleInterceptorBase`. Used by all sample rules for error pages.
  - `RuleInterceptorErrorHandlingCallback` — pre-built callback that takes `TemplateRenderer` + `ErrorHandlerConfigurationImpl`. Writes PA-formatted error page.
- **Impact:** The spec references `TemplateRenderer` and `ErrorHandlingCallback` but doesn't fully document `ResponseBuilder` which is needed for DENY mode.
- **Resolution:** Add `ResponseBuilder` to SDK API Surface. Document pattern for building error response in DENY mode.

### GAP-11: Test patterns missing Spring context setup

- **Severity:** 🟡 Medium
- **Category:** Testing Strategy
- **Spec location:** Lines 811–834 (Test Strategy)
- **Current spec says:** Mockito-based unit tests for adapter, rule, and config
- **SDK reality:** SDK samples use two test approaches:
  1. **Pure Mockito** — `ExternalAuthorizationRuleTest` (our planned approach): mock `Exchange`, `Identity`, `SessionStateSupport`, call `handleRequest()`, verify with `ArgumentCaptor`
  2. **Spring Test + Validator** — `TestAllUITypesAnnotationRule`: uses `@ContextConfiguration`, `SpringJUnit4ClassRunner`, injected `Validator` for Bean Validation
  - Additionally, `TestUtil.java` provides mock factories for `HeadersFactory`, `ConfigurationModelAccessorFactory`, etc.
- **Impact:** For `MessageTransformConfigTest` (config validation), we need Bean Validation support. We can either use Spring context like the samples, or use `Validation.buildDefaultValidatorFactory()` standalone.
- **Resolution:** Add testing approach note: config validation tests should use standalone `Validator` (no Spring needed, avoids extra test deps). Document the pure-Mockito patterns from `ExternalAuthorizationRuleTest`.

### GAP-12: Test dependency versions not specified

- **Severity:** 🟢 Low
- **Category:** Testing Strategy
- **Spec location:** Lines 811–834
- **Current spec says:** "Mock framework: Mockito (already in use)"
- **SDK reality (from sample POM):** Specific versions:
  - JUnit 4.13.2 (but our project uses JUnit 5 — Jupiter)
  - Mockito 5.15.2
  - Hamcrest 1.3
  - Spring Test/Context 6.2.11
  - Hibernate Validator 7.0.5.Final
- **Impact:** Version alignment with PA SDK for integration testing.
- **Resolution:** Note SDK sample dependency versions for reference. Our project uses JUnit 5 + Mockito (already in core module's `build.gradle.kts`) — document this deviation as intentional.

### GAP-13: Thread safety — `@Inject` fields vs method-local state

- **Severity:** 🟡 Medium
- **Category:** Non-Functional
- **Spec location:** Lines 873–880 (Constraints section, Constraint #4)
- **Current spec says:** "Plugin instances may be shared across threads. All adapter state must be method-local or immutable."
- **SDK reality:** SDK injects mutable fields:
  - `@Inject void setObjectMapper(ObjectMapper)` — shared, but ObjectMapper can be thread-safe if properly configured
  - `@Inject void setRenderer(TemplateRenderer)` / `setHttpClient(HttpClient)` — set once during construction, effectively immutable after init
  - `configure(T)` sets `configuration` field — called once before any request
  - The `ExternalAuthorizationRule` uses instance field `objectMapper` across threads
- **Impact:** The spec's "all state must be method-local" is too strict — `@Inject` fields and `configuration` are instance-level but effectively immutable after initialization. The spec should distinguish between "set-once at init" and "mutable per-request" state.
- **Resolution:** Refine thread safety constraint: "No mutable per-request state. Framework-injected fields (`@Inject`) and configuration are set once during initialization and are safe for concurrent access."

### GAP-14: Jackson relocation — version alignment check

- **Severity:** 🟡 Medium
- **Category:** Deployment
- **Spec location:** Lines 583–588 (FR-002-09 Jackson relocation)
- **Current spec says:** "If Jackson version alignment with PA is confirmed (same major.minor), relocation can be skipped — but this must be verified at build time."
- **SDK reality:** The SDK samples POM does NOT include Jackson as a dependency — it's provided by PingAccess at runtime. The SDK itself uses Jackson (`Identity.getAttributes()` returns `JsonNode`, `SessionStateSupport.setAttribute(String, JsonNode)`). The SDK jar imports `com.fasterxml.jackson.databind.*` — confirming Jackson is on PA's classpath.
- **Impact:** If our adapter bundles a different Jackson version and doesn't relocate, `ClassCastException` will occur when passing `JsonNode` between PA's Jackson and ours (they'd be different class instances from different classloaders). This is a **runtime failure** that only manifests when deployed.
- **Resolution:** Make Jackson relocation **mandatory** (not optional). The `Identity.getAttributes()` returning PA's `JsonNode` vs our shaded `JsonNode` is a concrete incompatibility vector. Alternative: use `compileOnly` for Jackson too (match PA's version) and don't shade — but this couples us to PA's Jackson version.

### GAP-15: `ServiceFactory` utility not documented

- **Severity:** 🟢 Low
- **Category:** SDK API Surface
- **Spec location:** Not mentioned
- **SDK reality:** `ServiceFactory` provides:
  - `bodyFactory()` → creates `Body` instances
  - `headersFactory()` → creates `Headers` instances
  - `configurationModelAccessorFactory()` → creates accessors for config model
  - `getSingleImpl(Class<T>)` / `getImplInstances(Class<T>)` — SPI discovery
- **Impact:** Useful for testing (create mock-compatible objects). `TestUtil.java` shows mock implementations of `HeadersFactory`.
- **Resolution:** Add `ServiceFactory` to SDK API Surface for reference. Document its utility in testing section.

---

## Summary Statistics

| Severity | Count |
|----------|-------|
| 🔴 High | 3 (GAP-01, GAP-02, GAP-09) |
| 🟡 Medium | 6 (GAP-03, GAP-04, GAP-07, GAP-11, GAP-13, GAP-14) |
| 🟢 Low | 6 (GAP-05, GAP-06, GAP-08, GAP-10, GAP-12, GAP-15) |
| **Total** | **15** |

## Notes

- GAP-09 (DENY mode NPE) is the **highest priority** — it's a runtime bug in the spec's design.
- GAP-01 and GAP-02 (Identity/Session) significantly expand the richness of `$session` context available to JSLT transforms.
- GAP-14 (Jackson relocation) needs a design decision — this affects the build architecture.

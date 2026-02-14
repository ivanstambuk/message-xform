# Feature 002 — PingAccess Adapter — Tasks

_Status:_ Ready
_Last updated:_ 2026-02-15

**Governing spec:** `docs/architecture/features/002/spec.md`
**Implementation plan:** `docs/architecture/features/002/plan.md`

> Keep this checklist aligned with the implementation plan increments. Every
> task must execute in test-first order (red -> green -> refactor) and record
> verification commands.

## Pre-Implementation Checklist

> See also: AGENTS.md § "Pre-Implementation Checklist (Mandatory)" for the
> full canonical list. The items below are feature-local prerequisites.

- [x] **C-002-01** — Spec exists and is marked `Status: Spec Ready`.
- [x] **C-002-02** — Implementation plan exists and is marked `Status: Ready`.
- [x] **C-002-03** — `adapter-pingaccess` module scaffold exists.
- [x] **C-002-04** — Open-question queue is empty for Feature 002.
- [x] **C-002-05** — Scenarios include S-002-01 through S-002-36, plus suffixed variants (S-002-09b, S-002-15b, S-002-33b).

## Increment → Task Mapping

| Increment | Tasks | Phase |
|-----------|-------|-------|
| I1 | T-002-01, T-002-02 | 1 — Configuration & Scaffold |
| I2 | T-002-03, T-002-04, T-002-05, T-002-06 | 2 — Adapter Bridge |
| I3 | T-002-07, T-002-08, T-002-09, T-002-10, T-002-10a, T-002-11, T-002-11a | 2 — Adapter Bridge |
| I4a | T-002-12, T-002-13, T-002-14 | 3 — Rule Lifecycle |
| I4b | T-002-15 _(gate)_, T-002-15a, T-002-16 | 3 — Rule Lifecycle |
| I5 | T-002-17 | 3 — Context |
| I6 | T-002-18, T-002-19 | 4 — Session Context |
| I7 | T-002-20, T-002-21, T-002-22, T-002-23, T-002-24 | 5 — Error Mode |
| I8 | T-002-25, T-002-26 | 6 — Hot Reload |
| I9 | T-002-27, T-002-28 | 6 — Observability |
| I10 | T-002-29, T-002-30 | 7 — Packaging |
| I11 | T-002-31, T-002-31a | 7 — Thread Safety |
| I12 | T-002-32 | 7 — Security |
| I13 | T-002-33 | 8 — Quality Gate |
| I14 | T-002-34 | 8 — Documentation |

## Task Checklist

### Phase 1 — Configuration & Scaffold

#### I1 — MessageTransformConfig + enums + Bean Validation (FR-002-04)

- [x] **T-002-01** — Implement `ErrorMode` and `SchemaValidation` enums (FR-002-04)
  _Intent:_ Provide typed configuration options for the admin UI.
  _Verify:_ `EnumTest` confirms enum values and serialization labels.
  _Verification commands:_
  - `./gradlew :adapter-pingaccess:test --tests "*EnumTest*"`

- [x] **T-002-02** — Implement `MessageTransformConfig` with validation and defaults (FR-002-04, S-002-17, S-002-18)
  _Intent:_ Define config model with `@UIElement`, `@Help`, `@NotNull`, `@Min`, `@Max`.
  _Test first:_ `MessageTransformConfigTest` for required fields, defaults, bounds.
  _Implement:_ `MessageTransformConfig extends SimplePluginConfiguration`.
  _Verify:_ Validation tests pass.
  _Verification commands:_
  - `./gradlew :adapter-pingaccess:test --tests "*MessageTransformConfigTest*"`

### Phase 2 — Adapter Bridge (Exchange <-> Message)

#### I2 — `wrapRequest()` mapping (FR-002-01)

- [x] **T-002-03** — Request URI split: path + query string (FR-002-01, S-002-06, S-002-27)
  _Test first:_ `PingAccessAdapterRequestTest.pathAndQueryMapping()` with URI containing `?`, multi-params, and no params.
  _Implement:_ Parse `Request.getUri()` into `requestPath` and `queryString`.
  _Verify:_ Path/query mapping tests pass.

- [x] **T-002-04** — Header mapping: single-value + multi-value normalized maps (FR-002-01, S-002-04)
  _Test first:_ `PingAccessAdapterRequestTest.headerMapping()` with mixed-case and repeated headers.
  _Implement:_ Convert `Map<String, String[]>` to `HttpHeaders.ofMulti(multiMap)` (provides both single-value and multi-value views).
  _Verify:_ Header normalization and first-value semantics tests pass.

- [x] **T-002-05** — Request body read + JSON parse fallback + bodyParseFailed tracking (FR-002-01, Constraint 5, S-002-01, S-002-07, S-002-08)
  _Test first:_ `PingAccessAdapterRequestTest.bodyReadAndParseFallback()` covering:
  - `!body.isRead()` -> `body.read()` invoked.
  - `IOException`/`AccessException` from `body.read()` -> `MessageBody.empty()` + warning.
  - malformed/non-JSON -> `MessageBody.empty()` + warning.
  - `bodyParseFailed` flag: `true` for non-JSON/read-failure, `false` for valid JSON or empty body (S-002-08).
  _Implement:_ Body pre-read, parse strategy, and `bodyParseFailed` flag tracking in `wrapRequest()`.
  _Verify:_ Body fallback and flag tests pass.

- [x] **T-002-06** — Request metadata mapping: method, content-type, request status, session placeholder (FR-002-01)
  _Test first:_ `PingAccessAdapterRequestTest.requestMetadataMapping()`.
  _Implement:_ Map `requestMethod`, `statusCode=null` and `session` placeholder (`SessionContext.empty()`).
  _Verify:_ Metadata mapping tests pass.

#### I3 — `wrapResponse()` + apply helpers (FR-002-01)

- [x] **T-002-07** — Response wrap mapping + debug log + bodyParseFailed tracking (FR-002-01, S-002-02, S-002-32)
  _Test first:_ `PingAccessAdapterResponseTest.wrapResponseMapping()` covering:
  - status/headers mapping
  - non-JSON fallback + `bodyParseFailed` flag set (parity with request side)
  - `body.read()` failure (`IOException`/`AccessException`) fallback + `bodyParseFailed` flag set
  - DEBUG log `"wrapResponse: {} {} -> status={}"`
  _Implement:_ `wrapResponse()` mapping behavior with `bodyParseFailed` tracking.
  _Verify:_ Wrap response tests pass.

- [x] **T-002-08** — Request-side apply logic (URL + method + body write path) (FR-002-01, S-002-06)
  _Test first:_ `ApplyChangesTest.applyRequestChanges()` for `setUri()` and `setMethod()`.
  _Implement:_ `applyRequestChanges()` helper.
  _Verify:_ Request-side apply tests pass.

- [x] **T-002-09** — Header diff strategy + protected-header exclusion (FR-002-01, Constraint 3, S-002-04)
  _Test first:_ `ApplyChangesTest.headerDiff()` for add/update/remove and exclusion of `content-length` / `transfer-encoding`.
  _Implement:_ Diff-based header application for request and response helpers.
  _Verify:_ Header diff tests pass.

- [x] **T-002-10** — Body replacement + content-type semantics (FR-002-01, S-002-01, S-002-02)
  _Test first:_ `ApplyChangesTest.bodyReplacement()` covering:
  - `setBodyContent()` on non-empty `MessageBody`
  - `application/json; charset=utf-8` set after body replacement
  - `MessageBody.empty()` passthrough (no body/content-type mutation)
  _Implement:_ Body write logic in apply helpers.
  _Verify:_ Body replacement tests pass.

- [x] **T-002-10a** — Compressed body fallback contract (Constraint 10, S-002-32)
  _Test first:_ `PingAccessAdapterResponseTest.compressedBodyFallback()` for `gzip`/`deflate`/`br` content-encoding.
  _Implement:_ Explicit v1 behavior: no decompression, parse fallback to `MessageBody.empty()` + warning.
  _Verify:_ Compressed body fallback tests pass.

- [x] **T-002-11** — Response status code application incl. PA non-standard passthrough (FR-002-01, S-002-05, S-002-35)
  _Test first:_ `ApplyChangesTest.statusCode()` with 200/404 and 277/477.
  _Implement:_ `HttpStatus.forCode()` in `applyResponseChanges()` with passthrough behavior.
  _Verify:_ Status code tests pass.

- [x] **T-002-11a** — SPI `applyChanges()` safety behavior (FR-002-01)
  _Test first:_ `ApplyChangesTest.spiApplyChangesThrowsUnsupported()`.
  _Implement:_ `applyChanges()` throws `UnsupportedOperationException`.
  _Verify:_ SPI safety test passes.

### Phase 3 — Rule Lifecycle, Metadata, SPI Registration, Context

#### I4a — MessageTransformRule core lifecycle (FR-002-02, FR-002-03, FR-002-05, FR-002-10)

- [x] **T-002-12** — Rule skeleton + annotation contract + callback (FR-002-02, FR-002-03, Constraint 1, S-002-03, S-002-17)
  _Test first:_ `RuleLifecycleTest.annotationAndCallback()` verifying `@Rule(destination = Site)` and non-null `getErrorHandlingCallback()`.
  _Implement:_ `MessageTransformRule extends AsyncRuleInterceptorBase<MessageTransformConfig>`.
  _Verify:_ Annotation and callback tests pass.

- [x] **T-002-13** — `configure()` engine init and config validation (FR-002-02, FR-002-05, S-002-09, S-002-10, S-002-15, S-002-17, S-002-18)
  _Test first:_ `RuleLifecycleTest.configureBoot()` for valid and invalid `specsDir`.
  _Implement:_ Parser/engine init and spec/profile load boot path.
  _Verify:_ Configure tests pass.

- [ ] **T-002-14** — Rule lifecycle cleanup hooks (FR-002-05)
  _Test first:_ `RuleLifecycleTest.shutdownLifecycle()`.
  _Implement:_ `@PreDestroy` cleanup path for managed resources.
  _Verify:_ Cleanup tests pass.

#### I4b — ExchangeProperty metadata + SPI registration (FR-002-07, FR-002-08)

- [x] **T-002-15** — **Prerequisite gate:** `TransformResult.specId()`/`.specVersion()` available (FR-002-07)
  _Depends on:_ **T-001-67 (Feature 001)** — per Principle 8 (Feature Ownership
  Boundaries), the core `TransformResult` extension is owned and implemented by
  Feature 001. This task is blocked until T-001-67 is complete.
  _Verify:_ `TransformResult.specId()` and `.specVersion()` exist in the core API.
  _Verification commands:_
  - `./gradlew :core:test --tests "*TransformResultSpecMetadataTest*"`

- [x] **T-002-15a** — `TransformResultSummary` + exchange properties (FR-002-07, S-002-21)
  _Test first:_ `ExchangePropertyTest.transformResultSummary()`.
  _Implement:_ `TRANSFORM_RESULT` + `TRANSFORM_DENIED` property declarations and writes.
  _Verify:_ Exchange property tests pass.

- [x] **T-002-16** — SPI registration via `AsyncRuleInterceptor` service file (FR-002-08, S-002-19)
  _Test first:_ `SpiRegistrationTest` uses `ServiceFactory.isValidImplName(AsyncRuleInterceptor.class, fqcn)`.
  _Implement:_ Add `META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor`.
  _Verify:_ SPI registration tests pass and `PingAccessAdapter` is not registered as plugin.

#### I5 — TransformContext construction (FR-002-13, S-002-22, S-002-23)

- [x] **T-002-17** — Build TransformContext (headers/query/cookies/status/session) with URI failure fallback
  _Test first:_ `ContextMappingTest` for:
  - headers mapping (single + multi-value views via `HttpHeaders`)
  - query/cookie first-value semantics
  - request status null / response status integer
  - `URISyntaxException` -> empty query map + warning
  _Implement:_ `buildTransformContext(Exchange, Integer)` and pass to 3-arg `engine.transform()`.
  _Verify:_ Context mapping tests pass.

### Phase 4 — Session Context Binding

#### I6 — Identity -> `$session` flat merge (FR-002-06, ADR-0030)

- [ ] **T-002-18** — Merge layers L1-L3 (identity, OAuth metadata, identity attributes) (S-002-13, S-002-25)
  _Test first:_ `IdentityMappingTest.layers123()` with precedence validation.
  _Implement:_ `buildSessionContext()` layers 1-3.
  _Verify:_ Layer merge tests pass.

- [ ] **T-002-19** — Merge layer L4 session state + null identity behavior (S-002-14, S-002-26)
  _Test first:_ `IdentityMappingTest.layer4PrecedenceAndNullIdentity()`.
  _Implement:_ SessionStateSupport merge and null-safe behavior.
  _Verify:_ L4 + null identity tests pass.

### Phase 5 — Error Mode Dispatch

#### I7 — PASS_THROUGH + DENY behavior (FR-002-11)

- [ ] **T-002-20** — Request SUCCESS/PASSTHROUGH/bodyParseFailed orchestration
  _Test first:_ `TransformFlowTest.requestSuccessAndPassthrough()` +
  `TransformFlowTest.bodyParseFailedSkipGuard()` (S-002-08):
  - when `bodyParseFailed`: body JSLT expression NOT evaluated, header/URL
    transforms still apply, original raw bytes forwarded unchanged,
    original Content-Type preserved, `Outcome.CONTINUE`.
  _Implement:_ Request dispatch, `Outcome.CONTINUE` paths, and `bodyParseFailed` skip-guard.
  _Verify:_ Request success/passthrough/skip-guard tests pass.

- [ ] **T-002-21** — Response SUCCESS/PASSTHROUGH orchestration
  _Test first:_ `TransformFlowTest.responseSuccessAndPassthrough()`.
  _Implement:_ Response dispatch paths.
  _Verify:_ Response success/passthrough tests pass.

- [ ] **T-002-22** — PASS_THROUGH error mode (S-002-11)
  _Test first:_ `TransformFlowTest.passThroughOnErrorRequestAndResponse()`.
  _Implement:_ Warning log + preserve original message paths.
  _Verify:_ PASS_THROUGH tests pass.

- [ ] **T-002-23** — DENY error mode for request/response + wrap failure branch (S-002-12)
  _Test first:_ `TransformFlowTest.denyOnErrorAndWrapFailure()`.
  _Implement:_
  - request: build RFC 9457 via `ResponseBuilder`, set response, return `Outcome.RETURN`
  - response: rewrite in-place to RFC 9457 502
  - wrap-response failure: adapter-generated RFC 9457 502 body
  _Verify:_ DENY tests pass.

- [ ] **T-002-24** — DENY guard in `handleResponse()` (S-002-28)
  _Test first:_ `TransformFlowTest.denyGuard()`.
  _Implement:_ Check `TRANSFORM_DENIED` and skip response processing.
  _Verify:_ Guard tests pass.

### Phase 6 — Hot Reload & Observability

#### I8 — Spec hot-reload scheduler (FR-002-04, FR-002-05, S-002-29, S-002-30, S-002-31)

- [ ] **T-002-25** — Scheduler lifecycle management
  _Test first:_ `HotReloadTest.threadLifecycle()`.
  _Implement:_ daemon single-thread scheduler + `@PreDestroy` shutdown.
  _Verify:_ Scheduler lifecycle tests pass.

- [ ] **T-002-26** — Reload task robustness and explicit path resolution
  _Test first:_ `HotReloadTest.successFailureAndConcurrentSwap()`.
  _Implement:_ Resolve specs/profile paths, call explicit `engine.reload(List<Path>, Path)`, retain previous registry on parse/compile/profile/io failures.
  _Verify:_ Reload behavior tests pass.

#### I9 — JMX MBean observability (FR-002-14, S-002-33, S-002-34)

- [ ] **T-002-27** — Metrics MXBean + counter implementation
  _Test first:_ `MessageTransformMetricsTest`.
  _Implement:_ MXBean + `LongAdder` counters and reset behavior.
  _Verify:_ Metrics unit tests pass.

- [ ] **T-002-28** — Rule wiring: register/unregister + counter updates
  _Test first:_ `JmxIntegrationTest` for opt-in/opt-out lifecycle and increment behavior.
  _Implement:_ Configure/shutdown wiring and conditional increment paths.
  _Verify:_ JMX integration tests pass.

### Phase 7 — Packaging, Thread Safety & Hardening

#### I10 — Shadow JAR + version guard (FR-002-09, FR-002-10, ADR-0035)

- [ ] **T-002-29** — Runtime version guard warning semantics (ADR-0035, S-002-36)
  _Intent:_ Detect PA runtime/compiled version mismatch and log WARN remediation (no fail-fast).
  _Test first:_ `VersionGuardTest` for match and mismatch behavior.
  _Implement:_ Version mismatch detection and warning message.
  _Verify:_ Version guard tests pass.

- [ ] **T-002-30** — Shadow JAR correctness verification (NFR-002-02, S-002-24)
  _Test first:_ `ShadowJarTest` for include/exclude assertions.
  _Implement:_ Verify JAR contains adapter + core + relocated core deps and excludes PA SDK/Jackson/SLF4J/Jakarta.
  _Verify:_ Shadow JAR checks pass.
  _Verification commands:_
  - `./gradlew :adapter-pingaccess:shadowJar`
  - `jar tf adapter-pingaccess/build/libs/adapter-pingaccess-*-SNAPSHOT.jar | grep -q "io/messagexform/core"`
  - `jar tf ... | grep -q "io/messagexform/internal/jackson"`
  - `jar tf ... | grep -q "com/schibsted/spt/data/jslt"`
  - `jar tf ... | grep -q "META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor"`
  - `jar tf ... | grep -c "com/pingidentity/pa/sdk"` → must be 0
  - `jar tf ... | grep "com/fasterxml/jackson"` → must be empty (catches MR-JAR leakage)
  - `jar tf ... | grep "META-INF/services/com.fasterxml.jackson"` → must be empty

#### I11 — Thread safety + performance (NFR-002-01, NFR-002-03, S-002-16, S-002-20)

- [ ] **T-002-31** — Concurrent stress test
  _Test first:_ `ConcurrentTest` with 10+ threads and independent assertions.
  _Implement:_ Concurrency harness for request/response paths.
  _Verify:_ No data races/corruption.

- [ ] **T-002-31a** — Performance budget verification (<10ms adapter overhead)
  _Test first:_ `PingAccessAdapterPerformanceTest` over 64KB payload samples.
  _Implement:_ Measurement harness excluding core transform time.
  _Verify:_ Average adapter overhead under target.

#### I12 — Security validation (FR-002-04 security note)

- [ ] **T-002-32** — Path validation hardening (S-002-18)
  _Test first:_ `SecurityPathTest` for `..`, file-vs-dir, unreadable path, and accepted valid directory.
  _Implement:_ Normalize and validate `specsDir`/`profilesDir` in `configure()`.
  _Verify:_ Security path tests pass.

### Phase 8 — Quality Gate & Documentation

#### I13/I14 — Final audit and documentation sync

- [ ] **T-002-33** — Full scenario coverage audit (NFR-002-05, all scenarios in `scenarios.md`)
  _Intent:_ Prove all scenarios have executable test evidence.
  _Verify:_ Coverage matrix updated with test method mapping.

- [ ] **T-002-34** — Documentation status sync
  _Intent:_ Sync roadmap, AGENTS mirror, knowledge map, `llms.txt`, and session state.
  _Verify:_ Docs reflect implementation state consistently.

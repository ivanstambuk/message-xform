# Feature 002 — PingAccess Adapter — Tasks

_Status:_ In Progress (Phase 1)
_Last updated:_ 2026-02-12

**Governing spec:** `docs/architecture/features/002/spec.md`
**Implementation plan:** `docs/architecture/features/002/plan.md`

> Keep this checklist aligned with the feature plan increments. Stage tests
> before implementation, record verification commands beside each task, and
> prefer bite-sized entries (≤90 minutes).
>
> When referencing requirements, keep feature IDs (`FR-`), non-functional IDs
> (`NFR-`), and scenario IDs (`S-002-`) in parentheses after the task title.

## Pre-Implementation Checklist (Rule 11)

- [x] **C-002-01** — Spec is marked `Status: Spec Ready` ✅
- [x] **C-002-02** — Implementation plan is marked `Status: Ready` ✅
- [x] **C-002-03** — `adapter-pingaccess` module scaffolded with Gradle 100% resolution ✅
- [x] **C-002-04** — CI failure resolved and Docker build fixed ✅
- [x] **C-002-05** — All 35 scenarios defined in `scenarios.md` ✅

## Task Checklist

### Phase 1 — Foundation: Configuration & Bridge SPI

#### I1 — MessageTransformConfig + enums + Bean Validation (FR-002-04)

- [x] **T-002-01** — Implement `ErrorMode` and `SchemaValidation` enums (FR-002-04) ✅ 2026-02-12
  _Intent:_ Provide typed configuration options for the Admin UI.
  _Verify:_ `EnumTest` confirms `toString()` labels match spec.
  _Verification commands:_
  - `./gradlew :adapter-pingaccess:test --tests "*EnumTest*"`

- [x] **T-002-02** — Implement `MessageTransformConfig` (FR-002-04) ✅ 2026-02-12
  _Intent:_ Define the plugin configuration model with PA UI annotations.
  _Test first:_ `MessageTransformConfigTest` verifies `@NotNull`, `@Min`, `@Max`.
  _Implement:_ Create `MessageTransformConfig` extending `SimplePluginConfiguration`.
  _Verify:_ All validation tests pass.
  _Verification commands:_
  - `./gradlew :adapter-pingaccess:test --tests "*MessageTransformConfigTest*"`

#### I2 — PingAccessAdapter.wrapRequest() (FR-002-01)

- [ ] **T-002-03** — Map request path and query string (FR-002-01, S-002-06)
  _Intent:_ Correctly split PA `Request.getUri()` into `path` and `query`.
  _Test first:_ `PingAccessAdapterRequestTest.pathMapping()` with URI containing `?`, multiple params, and no params.
  _Implement:_ Split logic in `wrapRequest`.
  _Verify:_ Test passes.

- [ ] **T-002-04** — Map request headers (FR-002-01, S-002-04)
  _Intent:_ Convert `Map<String, String[]>` to core `HttpHeaders`.
  _Test first:_ `PingAccessAdapterRequestTest.headerMapping()` with multi-value headers and case normalization check.
  _Implement:_ Header conversion logic.
  _Verify:_ Test passes.

- [ ] **T-002-05** — Body deep copy and state check (FR-002-01, ADR-0013, S-002-01)
  _Intent:_ Ensure body is read into memory before transformation.
  _Test first:_ `PingAccessAdapterRequestTest.bodyDeepCopy()` — verify `body.read()` is called if `!isRead()`.
  _Implement:_ `wrapRequest` body handling.
  _Verify:_ Test passes.

- [ ] **T-002-06** — Media type resolution (FR-002-01, S-002-08)
  _Intent:_ Resolve `MediaType` from `Content-Type` header.
  _Test first:_ `PingAccessAdapterRequestTest.mediaTypeResolution()` with various content types and structured suffixes.
  _Implement:_ Use `MediaType.fromContentType()`.
  _Verify:_ Test passes.

#### I3 — PingAccessAdapter.wrapResponse() + applyChanges() (FR-002-01)

- [ ] **T-002-07** — Implement `wrapResponse()` mapping (FR-002-01, S-002-02)
  _Intent:_ Map PA `Response` to core `Message`.
  _Test first:_ `PingAccessAdapterResponseTest` — verify status code and headers.
  _Implement:_ `wrapResponse` in `PingAccessAdapter`.
  _Verify:_ Test passes.

- [ ] **T-002-08** — Method override apply logic (FR-002-01, S-002-06)
  _Intent:_ Apply `Message.requestMethod()` and `requestPath()` back to PA.
  _Test first:_ `ApplyChangesTest.requestUriAndMethod()` — verify `setUri()` and `setMethod()`.
  _Implement:_ `applyRequestChanges` in `PingAccessAdapter`.
  _Verify:_ Test passes.

- [ ] **T-002-09** — Header diff-based application (FR-002-01, S-002-04)
  _Intent:_ Use the spec-mandated diff strategy for headers.
  _Test first:_ `ApplyChangesTest.headerDiff()` — verify `Headers.removeFields` and `Headers.setValues`.
  _Implement:_ Apply logic for request and response headers.
  _Verify:_ Test passes.

- [ ] **T-002-10** — Body replacement logic (FR-002-01, S-002-01, S-002-02)
  _Intent:_ Replace PA body and update `Content-Length`.
  _Test first:_ `ApplyChangesTest.bodyReplacement()` — verify `setBodyContent()` call.
  _Implement:_ Apply logic for request and response bodies.
  _Verify:_ Test passes.

- [ ] **T-002-11** — Status code passthrough (FR-002-01, S-002-05, S-002-35)
  _Intent:_ Map core status code to PA `HttpStatus`.
  _Test first:_ `ApplyChangesTest.statusCode()` with standard and non-standard PA status codes (277, 477).
  _Implement:_ `applyResponseChanges` using `HttpStatus.forCode()`.
  _Verify:_ Test passes.

---

### Phase 2 — Rule Core: Lifecycle & SPI Registration

#### I4a — MessageTransformRule core lifecycle (FR-002-02, FR-002-05)

- [ ] **T-002-12** — Skeletal `MessageTransformRule` & `@Rule` (FR-002-03, S-002-17)
  _Intent:_ Establish the plugin class and configuration link.
  _Test first:_ `RuleLifecycleTest` — verify `category = Processing` and `destination = Site`.
  _Implement:_ Create class extending `AsyncRuleInterceptorBase<MessageTransformConfig>`.
  _Verify:_ Compiles and annotation is correct.

- [ ] **T-002-13** — Engine initialization in `configure()` (FR-002-02, S-002-17)
  _Intent:_ Boot up the transformation engine from config.
  _Test first:_ `RuleLifecycleTest.engineBoot()` — verify `TransformEngine` is instantiated with correct `errorMode` and `schemaValidation` settings, and `loadSpec()` is called.
  _Implement:_ Implement `configure()` logic.
  _Verify:_ Test passes.

- [ ] **T-002-14** — Teardown in `@PreDestroy` (FR-002-05)
  _Intent:_ Clean up engine resources.
  _Test first:_ `RuleLifecycleTest.shutdown()` — verify engine shutdown is called.
  _Implement:_ Implement `shutdown()` with `@PreDestroy`.
  _Verify:_ Test passes.

#### I4b — ExchangeProperty metadata + SPI registration (FR-002-07, FR-002-08)

- [ ] **T-002-15** — Define `TransformResultSummary` & property (FR-002-07, S-002-21)
  _Intent:_ Metadata key for the Exchange.
  _Test first:_ `ExchangePropertyTest` — verify `create()` call with correct namespace.
  _Implement:_ Define `ExchangeProperty<TransformResultSummary>`.
  _Verify:_ Test passes.

- [ ] **T-002-16** — SPI registration (FR-002-08, S-002-19)
  _Intent:_ Make plugin discoverable by PingAccess.
  _Implement:_ Add to `META-INF/services/com.pingidentity.pa.sdk.policy.RuleInterceptor`.
  _Verify:_ JAR contains entry.
  _Verification commands:_
  - `grep -q "MessageTransformRule" adapter-pingaccess/src/main/resources/META-INF/services/com.pingidentity.pa.sdk.policy.RuleInterceptor`

---

### Phase 3 — Context & Identity Merging

#### I5 — TransformContext construction (FR-002-01, ADR-0030)

- [ ] **T-002-17** — Complete TransformContext construction (S-002-22, S-002-23, FR-002-13)
  _Intent:_ Map PA `Exchange` to core `TransformContext` including headers, cookies, query params, request URI, method, and protocol.
  _Test first:_ `ContextMappingTest` with various inputs.
  _Implement:_ Update `PingAccessAdapter.buildTransformContext()`.
  _Verify:_ Test passes.

#### I6 — Session context: Identity -> $session (FR-002-06, ADR-0030)

- [ ] **T-002-18** — Implement flat-merge identity layers (L1, L2, L3) (FR-002-06)
  _Intent:_ Merge basic identity, OAuth metadata, and claims.
  _Test first:_ `IdentityMappingTest` — verify precedence: Claims > OAuth > Identity.
  _Implement:_ Merging logic in `PingAccessAdapter`.
  _Verify:_ Test passes.

- [ ] **T-002-19** — Merge session state (L4) (FR-002-06, S-002-26)
  _Intent:_ Highest precedence layer from `SessionStateSupport`.
  _Test first:_ `IdentityMappingTest.sessionStatePrecedence()`.
  _Implement:_ Final merge layer.
  _Verify:_ Test passes.

---

### Phase 4 — Transformation Flow & Error Handling

#### I7 — Error mode dispatch: PASS_THROUGH + DENY (FR-002-11)

- [ ] **T-002-20** — Implement `handleRequest` transformation loop (FR-002-02, S-002-01)
  _Intent:_ Full SUCCESS path orchestration.
  _Test first:_ `TransformFlowTest` — mock engine success, verify adapter apply called.
  _Implement:_ Transformation logic in `handleRequest`.
  _Verify:_ Test passes.

- [ ] **T-002-21** — Implement `handleResponse` transformation loop (FR-002-02, S-002-02)
  _Intent:_ Full SUCCESS path for response phase.
  _Test first:_ `TransformFlowTest.responseSuccess()`.
  _Implement:_ Transformation logic in `handleResponse`.
  _Verify:_ Test passes.

- [ ] **T-002-22** — Error mode: PASS_THROUGH (S-002-11)
  _Intent:_ Safe fallback on error.
  _Test first:_ `TransformFlowTest.passThroughOnError()`.
  _Implement:_ Log warning and return original message.
  _Verify:_ Test passes.

- [ ] **T-002-23** — Error mode: DENY (S-002-12)
  _Intent:_ Reject on error with RFC 9457 and 502/503.
  _Test first:_ `TransformFlowTest.denyOnError()`.
  _Implement:_ Build error response on exchange.
  _Verify:_ Test passes.

- [ ] **T-002-24** — The DENY guard (S-002-28, GAP-4 fix)
  _Intent:_ Avoid double-processing or overwriting errors.
  _Test first:_ `TransformFlowTest.denyGuard()` — set property in request, verify skipped in response.
  _Implement:_ Skip logic in `handleResponse`.
  _Verify:_ Test passes.

---

### Phase 5 — Hot Reload & Concurrency

#### I8 — Spec hot-reload scheduler (FR-002-04, NFR-001-05)

- [ ] **T-002-25** — Scheduler thread management (S-002-29)
  _Intent:_ Managed background thread for reloading.
  _Test first:_ `HotReloadTest.threadLifecycle()` — verify start/stop.
  _Implement:_ `ScheduledExecutorService` in Rule.
  _Verify:_ Test passes.

- [ ] **T-002-26** — Robust reload task (S-002-30)
  _Intent:_ Reload implementation with failure safety.
  _Test first:_ `HotReloadTest.failureSafety()` — verify old specs kept on reload error.
  _Implement:_ Reload task with try/catch.
  _Verify:_ Test passes.

---

### Phase 6 — Observability

#### I9 — JMX MBean observability (FR-002-14)

- [ ] **T-002-27** — Metrics MBean & counters (S-002-33)
  _Intent:_ Expose stats via JMX.
  _Test first:_ `MessageTransformMetricsTest` — verify `LongAdder` increments.
  _Implement:_ Metrics MXBean.
  _Verify:_ Test passes.

- [ ] **T-002-28** — Rule metric wiring (S-002-34)
  _Intent:_ Hook Rule events to metrics.
  _Test first:_ `JmxIntegrationTest` — verify MBean created/destroyed based on config.
  _Implement:_ Registration logic in `configure()`.
  _Verify:_ Test passes.

---

### Phase 7 — Packaging & Hardening

#### I10 — Shadow JAR & version guard (FR-002-09, ADR-0035)

- [ ] **T-002-29** — Runtime version guard (ADR-0035)
  _Intent:_ Fail fast on Jackson version conflict.
  _Test first:_ `VersionGuardTest`.
  _Implement:_ Version check logic.
  _Verify:_ Test passes.

- [ ] **T-002-30** — Shadow JAR correctness verification (I10 Refinement, S-002-24)
  _Verify:_ `jar tf` check for zero `com.fasterxml.jackson` / `org.slf4j` leakage AND presence of required deps.
  _Verification commands:_
  - `./gradlew :adapter-pingaccess:shadowJar`
  - `jar tf adapter-pingaccess/build/libs/adapter-pingaccess-*-SNAPSHOT.jar > jar_contents.txt`
  - `grep -q "io/messagexform/core" jar_contents.txt`
  - `grep -q "com/schibsted/spt/data/jslt" jar_contents.txt`
  - `grep -vE "io/messagexform|META-INF|snakeyaml|jslt|networknt" jar_contents.txt | grep . && exit 1 || echo "Clean JAR"`

#### I11 — Thread safety + performance stress test (NFR-002-03, S-002-20)

- [ ] **T-002-31** — Concurrent stress test (S-002-20)
  _Intent:_ Verify no race conditions.
  _Test first:_ `ConcurrentTest` with 50 threads.
  _Verify:_ Test passes.

#### I12 — Security validation (NFR-002-06, ADR-0032)

- [ ] **T-002-32** — Path traversal protection (S-002-18)
  _Intent:_ Block `..` in directory config.
  _Test first:_ `SecurityPathTest`.
  _Implement:_ Path normalization and check in `configure()`.
  _Verify:_ Test passes.

#### I12a — ArchUnit validation (NFR-002-04, NFR-002-02)

- [ ] **T-002-33** — ArchUnit automation
  _Intent:_ Prevent reflection and layer leakage.
  _Test first:_ `AdapterArchTest`.
  _Verify:_ Test passes.

---

### Phase 8 — E2E & Final Handover

#### I13/I14 — Final Audit

- [ ] **T-002-34** — 35-scenario coverage audit (S-002-01 to S-002-35)
- [ ] **T-002-35** — Documentation status sync

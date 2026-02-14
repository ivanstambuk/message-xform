# Feature 002 — PingAccess Adapter — Implementation Plan

_Linked specification:_ `docs/architecture/features/002/spec.md`
_Linked tasks:_ `docs/architecture/features/002/tasks.md`
_Status:_ Complete (all tasks implemented, FR-002-12 Docker E2E validated)
_Last updated:_ 2026-02-14

> Guardrail: Keep this plan traceable back to the governing spec. Reference
> FR/NFR/Scenario IDs from `spec.md` where relevant, log any new high- or
> medium-impact questions in `docs/architecture/open-questions.md`, and assume
> clarifications are resolved only when the spec's normative sections and,
> where applicable, ADRs under `docs/decisions/` have been updated.

## Vision & Success Criteria

The PingAccess Adapter bridges the message-xform core engine into PingAccess
9.0 as a deployable Java plugin. Success means:

- A single shadow JAR dropped into `/opt/server/deploy/` makes a "Message
  Transform" rule type available in the PingAccess admin console.
- Administrators configure a spec directory, error mode, and profile — the
  adapter loads specs and transforms request/response bodies, headers, status
  codes, and URLs via JSLT expressions.
- The `$session` JSLT variable exposes PingAccess identity (subject, claims,
  OAuth metadata, session state) for identity-aware transforms.
- Error modes (`PASS_THROUGH` / `DENY`) provide safe, predictable failure
  handling matching the RFC 9457 error format used across the project.
- Opt-in JMX MBeans provide runtime observability for PA administrators.

**Measurable signals:**

- All scenarios defined in `scenarios.md` pass as unit tests.
- Shadow JAR < 5 MB (NFR-002-02).
- Adapter transform overhead < 10 ms for < 64 KB body (NFR-002-01).
- Zero PA SDK classes in shadow JAR — verified via `jar tf`.
- Thread-safe: concurrent test with 10 threads produces correct results (NFR-002-03).
- No reflection used (NFR-002-04, enforced by FR-009-12 ArchUnit tests).
- Compiles with `-Xlint:all -Werror` on Java 21 (NFR-002-05).

## Scope Alignment

- **In scope:** FR-002-01 through FR-002-11, FR-002-13, FR-002-14; all NFRs
  (NFR-002-01 through NFR-002-05); all scenarios defined in `scenarios.md`;
  SPI registration and shadow JAR packaging.
- **Out of scope (for this implementation pass):** Agent deployment
  (N-002-01). Groovy scripts (N-002-02). Async HTTP calls (N-002-03).
  Third-party metrics frameworks (N-002-05).

## Prerequisites

- [x] Feature spec is `Status: Spec Ready`
- [x] All high-/medium-impact open questions resolved (0 open)
- [x] Related ADRs accepted: ADR-0013, ADR-0020, ADR-0022, ADR-0024, ADR-0025,
      ADR-0027, ADR-0030, ADR-0031, ADR-0032, ADR-0033, ADR-0034, ADR-0035
- [x] `adapter-pingaccess` Gradle module scaffolded (compileOnly deps, shadow
      JAR excludes, PaVersionGuard, `pa-compiled-versions.properties`)
- [x] PA SDK JAR available at `libs/pingaccess-sdk/pingaccess-sdk-9.0.1.0.jar`
- [x] Core engine stable (Feature 001 Phases 1–10 complete, all tests green;
      T-001-67 required before I4b — see Phase 3 preconditions)

## Dependencies & Interfaces

| Dependency | Notes |
|------------|-------|
| `core` module (Feature 001) | `TransformEngine`, `TransformRegistry`, `Message`, `TransformResult`, `TransformContext`, `GatewayAdapter` SPI, `SpecParser`, `Direction` |
| PA SDK 9.0.1.0 | `AsyncRuleInterceptorBase`, `Exchange`, `Request`, `Response`, `Body`, `Headers`, `Identity`, `SessionStateSupport`, `OAuthTokenMetadata`, `ExchangeProperty`, `ResponseBuilder`, `@Rule`, `@UIElement`, `HttpStatus`, `ServiceFactory` |
| Jackson 2.17.0 | PA-provided (`compileOnly`). `ObjectMapper`, `JsonNode`, `ObjectNode`. Shared classloader — no relocation. |
| Jakarta Validation 3.1.1 | PA-provided (`compileOnly`). `@NotNull`, `@Min`, `@Max` on config fields. |
| Jakarta Inject 2.0.1 | PA-provided (`compileOnly`). `@Inject` for lifecycle injection. |
| SLF4J 1.7.36 | PA-provided (`compileOnly`). PA routes to Log4j2. |
| `adapter-standalone` | Reference pattern — `StandaloneAdapter`, `ProxyHandler` (read-only reference, no code dependency). |

## Assumptions & Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| PA SDK mocks may not cover all `Body` state transitions | **Medium** — Test accuracy | Use `ServiceFactory.bodyFactory()` for integration-level tests where Mockito is insufficient. |
| Jackson version mismatch (core relocates Jackson, adapter uses PA's Jackson) | **Low** — Core's anti-corruption layer (ADR-0032) isolates via byte boundary | Core exposes `byte[]`/`Map` interface, not `JsonNode`. Adapter uses PA's Jackson directly. Already resolved by architecture. |
| `@PreDestroy` may not be called on ungraceful PA shutdown | **Low** — Resource leak (reload scheduler thread) | Daemon thread flag as safety net (already specified in FR-002-04). |
| PA SDK test infrastructure (`ServiceFactory`) may have undocumented behaviors | **Low** — Test reliability | Fall back to Mockito mocks; use `ServiceFactory` selectively. |
| JMX MBean registration may fail if ObjectName conflicts | **Low** — Non-fatal | Guard with `isRegistered()` check (already specified in FR-002-14). |

## Implementation Drift Gate

After each phase, run the quality gate and verify scenario coverage:

```bash
./gradlew --no-daemon spotlessApply check
./gradlew --no-daemon :adapter-pingaccess:test
```

Cross-check each completed increment's FR/NFR claims against the spec.

### Drift Gate Checklist (for agents)

- [ ] Spec/plan/tasks updated to the current date.
- [ ] `docs/architecture/open-questions.md` has no `Open` entries for Feature 002.
- [ ] Verification commands have been run and logged in `_current-session.md`.
- [ ] For each FR/NFR, confirm that the implementation matches the spec.
- [ ] Any drift (spec says X, code does Y) is logged as an open question or
      fixed immediately, depending on severity.
- [ ] Scenario coverage matrix in `scenarios.md` cross-referenced with tests.

### Drift Report

_To be completed after implementation._

#### FR Traceability Matrix

| FR | Description | Implementation | Test Evidence |
|----|-------------|----------------|---------------|
| FR-002-01 | GatewayAdapter implementation | | |
| FR-002-02 | AsyncRuleInterceptor plugin | | |
| FR-002-03 | @Rule annotation | | |
| FR-002-04 | Plugin configuration (admin UI) | | |
| FR-002-05 | Plugin lifecycle | | |
| FR-002-06 | Session context binding | | |
| FR-002-07 | ExchangeProperty state | | |
| FR-002-08 | SPI registration | | |
| FR-002-09 | Deployment packaging | | |
| FR-002-10 | Gradle module setup | | |
| FR-002-11 | Error handling | | |
| FR-002-12 | Docker E2E test script | `scripts/pa-e2e-test.sh` | `e2e-results.md` — 50/50 pass, 22 scenarios validated E2E (2026-02-14) |
| FR-002-13 | TransformContext construction | | |
| FR-002-14 | JMX observability (opt-in) | | |

#### NFR Verification

| NFR | Target | Evidence | Status |
|-----|--------|----------|--------|
| NFR-002-01 | Adapter overhead < 10 ms for < 64 KB body | | ⬜ |
| NFR-002-02 | Shadow JAR < 5 MB | | ⬜ |
| NFR-002-03 | Thread-safe — no mutable per-request state | | ⬜ |
| NFR-002-04 | No reflection | | ⬜ |
| NFR-002-05 | Java 21 `-Xlint:all -Werror` | | ⬜ |

---

## Increment Map

### Phase 1 — Configuration & Plugin Scaffold (≤90 min)

1. **I1 — MessageTransformConfig + enums + Bean Validation** (≤90 min)
   - _Goal:_ Implement `MessageTransformConfig` (extends
     `SimplePluginConfiguration`) with all `@UIElement`-annotated fields,
     `ErrorMode` and `SchemaValidation` enums, and `@Help` annotations.
     Implement `MessageTransformConfigTest` with standalone Jakarta Bean
     Validation.
   - _Preconditions:_ Module scaffold exists (compileOnly deps in place).
   - _Steps:_
     1. **Test first:** Write `MessageTransformConfigTest`:
        - Required field validation: `specsDir` absent → constraint violation.
        - Default values: `errorMode = PASS_THROUGH`, `reloadIntervalSec = 0`,
          `schemaValidation = LENIENT`, `enableJmxMetrics = false`.
        - Range validation: `reloadIntervalSec` with `@Min(0) @Max(86400)`.
        - Enum serialization: `ErrorMode` and `SchemaValidation` values.
     2. Implement `ErrorMode` enum with `PASS_THROUGH` and `DENY`.
     3. Implement `SchemaValidation` enum with `STRICT` and `LENIENT`.
     4. Implement `MessageTransformConfig` with `@UIElement` annotations,
        `@Help` annotations, and Bean Validation constraints.
     5. Run tests, verify all pass.
   - _Requirements covered:_ FR-002-04 (plugin configuration), S-002-17,
     S-002-18.
   - _Commands:_ `./gradlew :adapter-pingaccess:test`,
     `./gradlew spotlessApply check`
   - _Exit:_ Config class compiles, all 7 fields annotated, Bean Validation
     tests pass. Enums round-trip correctly.

### Phase 2 — Adapter Bridge: Exchange ↔ Message (≤2 × 90 min)

2. **I2 — PingAccessAdapter.wrapRequest()** (≤90 min)
   - _Goal:_ Implement `PingAccessAdapter.wrapRequest(Exchange)` mapping all
     7 `Message` fields from the PA `Exchange`/`Request`/`Body`/`Headers` model.
   - _Preconditions:_ I1 complete (config exists for constructor injection).
   - _Steps:_
     1. **Test first:** Write `PingAccessAdapterTest.wrapRequest*` tests:
        - Normal JSON body → `Message.body()` is `MessageBody.json(bytes)`.
        - Empty body → `MessageBody.empty()`.
        - Non-JSON body (`text/plain`) → `MessageBody.empty()` + warning logged.
        - Body read failure (`body.isRead() == false`, `body.read()` throws
          `IOException`) → `MessageBody.empty()` + warning logged.
        - Body read failure (`AccessException` from `body.read()`) →
          `MessageBody.empty()` + warning logged.
        - `bodyParseFailed` flag: `true` for non-JSON/read-failure, `false`
          for valid JSON or genuinely empty body. Tracked as adapter-internal
          state (e.g., field or wrapper record) for use in apply-phase
          skip-guard (S-002-08).
        - Headers → `HttpHeaders.ofMulti()` from PA's `Map<String, String[]>`.
        - Request path → from `Request.getUri()` (path portion only).
        - Request method → from `Request.getMethod().getName()`.
        - Query string → from `Request.getUri()` (after `?`).
        - Status code → `null` (requests have no status, ADR-0020).
        - Session → `SessionContext.empty()` for now (FR-002-06 in Phase 4).
     2. Implement `PingAccessAdapter.wrapRequest()` with body pre-read,
        JSON validation with `MessageBody.empty()` fallback, `bodyParseFailed`
        flag tracking, and header normalization.
     3. Run tests, verify all pass.
   - _Requirements covered:_ FR-002-01 (wrapRequest mapping table), S-002-01,
     S-002-07, S-002-08, S-002-27.
   - _Commands:_ `./gradlew :adapter-pingaccess:test`,
     `./gradlew spotlessApply check`
   - _Exit:_ `wrapRequest()` maps all 7 fields. Deep-copy verified. Null/empty
     edge cases handled.

3. **I3 — PingAccessAdapter.wrapResponse() + applyChanges()** (≤90 min)
   - _Goal:_ Implement `wrapResponse(Exchange)` and the two direction-specific
     `applyRequestChanges()` / `applyResponseChanges()` helper methods, plus
     the diff-based header application strategy.
   - _Preconditions:_ I2 complete.
   - _Steps:_
     1. **Test first:** Write `PingAccessAdapterTest.wrapResponse*` tests:
        - Normal JSON response body → `MessageBody.json(bytes)`.
        - Non-JSON response body → `MessageBody.empty()` + warning.
        - Compressed response body (`Content-Encoding: gzip`/`deflate`/`br`)
          → no decompression in v1, parse fallback to `MessageBody.empty()` +
          warning (Constraint 10).
        - `body.read()` failure (`IOException` or `AccessException`) →
          `MessageBody.empty()` + warning (Constraint 5 parity with request side).
        - Status code → `exchange.getResponse().getStatusCode()`.
        - Headers → `HttpHeaders.ofMulti()` (same normalization as request).
        - `requestPath` and `requestMethod` from original request.
        - DEBUG log emitted: `"wrapResponse: {} {} → status={}"` (spec
          lines 206–209).
     2. **Test first:** Write `PingAccessAdapterTest.applyRequestChanges*` tests:
        - Body → `transformedMessage.body().content()` bytes →
          `setBodyContent()` called.
        - Headers diff: added/updated/removed correctly.
        - Protected headers (`content-length`, `transfer-encoding`) excluded
          from diff.
        - URI rewrite → `setUri()` called.
        - Method override → `setMethod()` called.
        - `MessageBody.empty()` → `setBodyContent()` NOT called (passthrough).
        - After `setBodyContent()`: Content-Type set to
          `application/json; charset=utf-8` (FR-002-01).
          For `MessageBody.empty()`: Content-Type NOT modified.
     3. **Test first:** Write `applyResponseChanges*` tests:
        - Body → `setBodyContent()` on response.
        - Status code → `setStatus(HttpStatus.forCode())`.
        - Headers diff on response side.
        - `MessageBody.empty()` body → passthrough.
        - **Negative test (Constraint 3):** `applyResponseChanges()` does
          NOT call `setUri()` or `setMethod()` — request-side writes are
          invalid during response phase.
     4. **Test first:** Write test that `applyChanges()` (SPI method) throws
        `UnsupportedOperationException`.
     5. Implement `wrapResponse()`.
     6. Implement `applyRequestChanges()` and `applyResponseChanges()` with
        diff-based header strategy and protected header exclusion.
     7. Implement `applyChanges()` → throw `UnsupportedOperationException`.
     8. Run tests, verify all pass.
   - _Requirements covered:_ FR-002-01 (wrapResponse, applyChanges),
     Constraint 3, Constraint 10, S-002-02, S-002-04, S-002-05, S-002-06,
     S-002-32.
   - _Commands:_ `./gradlew :adapter-pingaccess:test`,
     `./gradlew spotlessApply check`
   - _Exit:_ Complete adapter bridge: wrap + apply for both directions. Header
     diff works. Protected headers excluded.

### Phase 3 — Plugin Rule: MessageTransformRule (≤3 × 90 min)

4a. **I4a — MessageTransformRule core lifecycle** (≤90 min)
   - _Goal:_ Implement `MessageTransformRule` extending
     `AsyncRuleInterceptorBase<MessageTransformConfig>` with `configure()`,
     `handleRequest()`, `handleResponse()`, and `getErrorHandlingCallback()`
     for the SUCCESS and PASSTHROUGH paths. Implement `@Rule` annotation.

   > **Return type awareness (Constraint 6):** `handleRequest()` returns
   > `CompletionStage<Outcome>` — can halt pipeline via `Outcome.RETURN`.
   > `handleResponse()` returns `CompletionStage<Void>` — cannot halt, only
   > rewrite. Error-mode dispatch (I7) depends on this distinction.
   - _Preconditions:_ I3 complete (adapter bridge works).
   - _Steps:_
     1. **Test first:** Write `MessageTransformRuleTest`:
        - `configure()` with valid config → engine initialized, specs loaded.
        - `configure()` with invalid `specsDir` → `ValidationException`.
        - `@Rule(destination = Site)` present (Constraint 1 — site-only
          deployment).
        - `handleRequest()` SUCCESS → body/headers/URL transformed, changes
          applied to exchange, `Outcome.CONTINUE` returned.
        - `handleRequest()` PASSTHROUGH (no match) → exchange untouched,
          `Outcome.CONTINUE`.
        - `handleResponse()` SUCCESS → response body/status transformed.
        - `handleResponse()` PASSTHROUGH → response untouched.
        - `handleRequest()` with `bodyParseFailed` → body JSLT expression
          NOT evaluated, header/URL transforms still apply, original raw
          bytes forwarded unchanged, `Outcome.CONTINUE` (S-002-08).
     2. **Test first:** Write test: `getErrorHandlingCallback()` returns a
        valid `RuleInterceptorErrorHandlingCallback` instance and does not
        throw (FR-002-02 — this method is abstract on `AsyncRuleInterceptor`
        and MUST be implemented).
     3. Implement `MessageTransformRule`:
        - `@Rule` annotation with `category`, `destination`, `label`,
          `type`, `expectedConfiguration`.
        - `configure()`: validate `specsDir`, init `SpecParser`, init
          `TransformEngine`, load specs, optionally load profiles.
         - `handleRequest()`: build context → wrap → check `bodyParseFailed`:
           if set, skip body expression, apply header/URL/method transforms,
           forward original raw bytes unchanged (S-002-08); otherwise
           transform → dispatch (SUCCESS → apply, PASSTHROUGH → no-op) →
           return `Outcome.CONTINUE`.
        - `handleResponse()`: same flow for response direction.
        - `getErrorHandlingCallback()`: return
          `RuleInterceptorErrorHandlingCallback`.
     4. Run tests, verify all pass.
   - _Requirements covered:_ FR-002-02, FR-002-03, FR-002-05, FR-002-10,
     S-002-01, S-002-02, S-002-03, S-002-09, S-002-10, S-002-15, S-002-18.
   - _Commands:_ `./gradlew :adapter-pingaccess:test`,
     `./gradlew spotlessApply check`
   - _Exit:_ Plugin configures, processes requests/responses for SUCCESS and
     PASSTHROUGH paths. `getErrorHandlingCallback()` verified.

4b. **I4b — ExchangeProperty metadata + SPI registration** (≤45 min)
   - _Goal:_ Implement `TransformResultSummary` record, `ExchangeProperty`
     declarations, `TRANSFORM_DENIED` property, and SPI service registration.
     Wire `TransformResultSummary` into `handleRequest()`/`handleResponse()`.
   - _Preconditions:_ I4a complete. **T-001-67 (Feature 001) complete** —
     `TransformResult.specId()`/`.specVersion()` must be available in core
     (Principle 8 — Feature Ownership Boundaries).
   - _Steps:_
     1. Implement `TransformResultSummary` record (FR-002-07), sourcing
        `specId`/`specVersion` from `TransformResult.specId()`/`.specVersion()`.
     2. **Test first:** Write `MessageTransformRuleTest`:
        - `TransformResultSummary` set on `ExchangeProperty` after transform.
        - `TRANSFORM_DENIED` ExchangeProperty declared correctly.
     3. **Test first:** Write `SpiRegistrationTest`:
        - `ServiceFactory.isValidImplName(AsyncRuleInterceptor.class, fqcn)`.
        - `PingAccessAdapter` is NOT registered as SPI.
     4. Create SPI file: `META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor`.
     5. Wire `TransformResultSummary` into handleRequest/handleResponse.
     6. Run tests, verify all pass.
   - _Requirements covered:_ FR-002-07, FR-002-08, S-002-19, S-002-21.
   - _Commands:_ `./gradlew :adapter-pingaccess:test`,
     `./gradlew spotlessApply check`
   - _Exit:_ `TransformResultSummary` on exchange after each transform. SPI
     registered. `TRANSFORM_DENIED` property available for I7.

5. **I5 — TransformContext construction** (≤90 min)
   - _Goal:_ Implement `PingAccessAdapter.buildTransformContext()` mapping
     PA Exchange data to `TransformContext` (headers, cookies, query params,
     status). Wire into `MessageTransformRule.handleRequest/Response()`.
   - _Preconditions:_ I4b complete.
   - _Steps:_
     1. **Test first:** Write `PingAccessAdapterTest.buildTransformContext*`
        tests:
        - Headers → single-value + multi-value, lowercase normalized.
        - Query params → `getQueryStringParams()` flattened, first-value.
        - Cookies → `getCookies()` flattened, first-value.
        - Status = `null` for request, integer for response.
        - `URISyntaxException` from `getQueryStringParams()` → empty map +
          warning logged.
     2. Implement `buildTransformContext(Exchange, Integer status)`.
     3. Wire `buildTransformContext()` into `handleRequest()` and
        `handleResponse()` — pass result to 3-arg `engine.transform()`.
     4. **Test first:** Add tests to `MessageTransformRuleTest`:
        - JSLT expression using `$cookies.session_token` → resolves.
        - JSLT expression using `$queryParams.page` → resolves.
     5. Run tests, verify all pass.
   - _Requirements covered:_ FR-002-13, S-002-22, S-002-23.
   - _Commands:_ `./gradlew :adapter-pingaccess:test`,
     `./gradlew spotlessApply check`
   - _Exit:_ `$cookies`, `$queryParams`, `$headers`, `$status` available in
     JSLT expressions.

### Phase 4 — Session Context Binding (≤90 min)

6. **I6 — Session context: Identity → $session** (≤90 min)
   - _Goal:_ Implement the 4-layer flat merge of `Identity`, `OAuthTokenMetadata`,
     `Identity.getAttributes()`, and `SessionStateSupport.getAttributes()` into
     a `Map<String, Object>` wrapped as `SessionContext.of(map)` for `$session`.
     Wire into `wrapRequest()` / `wrapResponse()` via `Message.session()`.
   - _Preconditions:_ I5 complete (TransformContext construction works).
   - _Steps:_
     1. **Test first:** Write `PingAccessAdapterTest.buildSessionContext*`
        tests:
        - Layer 1: `Identity` getters → `subject`, `mappedSubject`,
          `trackingId`, `tokenId`, `tokenExpiration` (ISO string).
        - Layer 2: `OAuthTokenMetadata` → `clientId`, `scopes`, `tokenType`,
          `realm`, `tokenExpiresAt`, `tokenRetrievedAt`.
        - Layer 3: `Identity.getAttributes()` → spread into flat map,
          overrides L1 keys.
        - Layer 4: `SessionStateSupport.getAttributes()` → spread, overrides
          all prior layers.
        - Null identity → `session = SessionContext.empty()`.
        - Null `OAuthTokenMetadata` → skip L2.
        - Null `tokenExpiration` → field omitted.
     2. Implement `buildSessionContext(Exchange)` method.
     3. Wire session context into `wrapRequest()` and `wrapResponse()`.
     4. **Test first:** Add to `MessageTransformRuleTest`:
        - JSLT `$session.subject` → resolves to identity subject.
        - JSLT `$session.clientId` → resolves to OAuth client ID.
        - JSLT `$session.scopes` → resolves to array.
        - JSLT `$session.authzCache` → resolves to session state value.
        - Unauthenticated → `$session` is null, JSLT handles gracefully.
     5. Run tests, verify all pass.
   - _Requirements covered:_ FR-002-06, S-002-13, S-002-14, S-002-25,
     S-002-26.
   - _Commands:_ `./gradlew :adapter-pingaccess:test`,
     `./gradlew spotlessApply check`
   - _Exit:_ Full `$session` merge works. All 4 layers tested. Null identity
     handled.

### Phase 5 — Error Handling (≤90 min)

7. **I7 — Error mode dispatch: PASS_THROUGH + DENY** (≤90 min)
   - _Goal:_ Implement error-mode dispatch in `handleRequest()` and
     `handleResponse()`. For `PASS_THROUGH`: log + continue. For `DENY`:
     build RFC 9457 error response via `ResponseBuilder` (request phase) or
     rewrite response in-place (response phase). Implement the DENY guard in
     `handleResponse()`.

   > **Return type awareness (Constraint 6):** `handleRequest()` returns
   > `CompletionStage<Outcome>` — the adapter can halt the pipeline via
   > `Outcome.RETURN`. `handleResponse()` returns `CompletionStage<Void>` —
   > the adapter **cannot** prevent response delivery, only rewrite in-place.
   > This is why DENY-response rewrites the body/status rather than returning
   > an `Outcome`.
   - _Preconditions:_ I6 complete (full request/response path works).
   - _Steps:_
     1. **Test first:** Write `MessageTransformRuleTest.errorMode*` tests:
        - PASS_THROUGH + request error → warning logged, original body
          forwarded, `Outcome.CONTINUE`.
        - PASS_THROUGH + response error → warning logged, original response
          preserved.
        - DENY + request error → `ResponseBuilder` builds error response,
          `exchange.setResponse()` called, `TRANSFORM_DENIED` property set,
          `Outcome.RETURN`.
        - DENY + response error → response body/status rewritten to 502 +
          RFC 9457 JSON.
        - DENY + `wrapResponse()` failure path (e.g., body read/parse
          failure before engine call) → adapter-generated RFC 9457 error body
          with status 502; no `AccessException` thrown.
        - DENY + `handleResponse()` after DENY `handleRequest()` → DENY
          guard fires, processing skipped, error response preserved.
     2. Implement error dispatch in `handleRequest()`:
        - On `TransformResult.ERROR`: check `errorMode`.
        - PASS_THROUGH → log warning, return `Outcome.CONTINUE`.
        - DENY → build error Response, set on exchange, set
          `TRANSFORM_DENIED` property, return `Outcome.RETURN`.
     3. Implement error dispatch in `handleResponse()`:
        - DENY guard check first: if `TRANSFORM_DENIED` → return immediately.
        - On `TransformResult.ERROR`: check `errorMode`.
        - PASS_THROUGH → log warning, leave response untouched.
        - DENY → rewrite response body to RFC 9457, status to 502.
     4. **Test first:** Write test: PA-specific status codes 277 (`ALLOWED`)
        and 477 (`REQUEST_BODY_REQUIRED`) from the backend are passed through
        unchanged by the adapter — not mapped to or from (Constraint 9).
     5. Run tests, verify all pass.
   - _Requirements covered:_ FR-002-11, S-002-11, S-002-12, S-002-28, S-002-35,
     Constraint 9.
   - _Commands:_ `./gradlew :adapter-pingaccess:test`,
     `./gradlew spotlessApply check`
   - _Exit:_ Both error modes work for both directions. DENY guard prevents
     response overwrite. RFC 9457 error body verified.

### Phase 6 — Hot Reload & Observability (≤2 × 90 min)

8. **I8 — Spec hot-reload scheduler** (≤90 min)
   - _Goal:_ Implement the `ScheduledExecutorService`-based spec reload when
     `reloadIntervalSec > 0`. Daemon thread, `@PreDestroy` cleanup, reload
     failure resilience.
   - _Preconditions:_ I7 complete.
   - _Steps:_
     1. **Test first:** Write `MessageTransformRuleTest.reload*` tests:
        - `reloadIntervalSec = 0` → no executor started.
        - `reloadIntervalSec > 0` → executor started, reload fires.
        - Reload success → new specs available.
        - Reload failure (malformed YAML) → previous registry retained,
          warning logged.
        - Concurrent reload during active transform → no corruption.
        - `@PreDestroy` → `shutdownNow()` called.
      2. Implement reload scheduler in `configure()`:
         - `newSingleThreadScheduledExecutor` with daemon thread and
           `mxform-spec-reload` name.
         - `scheduleAtFixedRate` with `reloadIntervalSec` interval.
         - Reload task calls `TransformEngine.reload(List<Path>, Path)`
           (note: **not** zero-arg — the engine requires explicit paths):
           - **Spec discovery:** `Files.walk(specsDir, 1)` filtered to
             `.yaml`/`.yml` extensions, collected to `List<Path>`.
           - **Profile resolution:** if `activeProfile` is non-empty,
             resolve `profilesDir/<activeProfile>.yaml`; else pass `null`.
         - Catch `SpecParseException`, `ExpressionCompileException`,
           `ProfileResolveException`, and `IOException` → log warning,
           retain previous registry.
     3. Implement `@PreDestroy shutdown()`: `executor.shutdownNow()`.
     4. Run tests, verify all pass.
   - _Requirements covered:_ FR-002-04 (reloadIntervalSec), FR-002-05
     (teardown), S-002-29, S-002-30, S-002-31.
   - _Commands:_ `./gradlew :adapter-pingaccess:test`,
     `./gradlew spotlessApply check`
   - _Exit:_ Hot reload works. Failure resilience verified. Daemon thread
     shutdown clean.

9. **I9 — JMX MBean observability** (≤90 min)
   - _Goal:_ Implement `MessageTransformMetricsMXBean` interface and
     `MessageTransformMetrics` implementation with `LongAdder` counters.
     Register/unregister MBean based on `enableJmxMetrics` config. Wire
     counter increments into `handleRequest()` / `handleResponse()`.
   - _Preconditions:_ I8 complete.
   - _Steps:_
     1. **Test first:** Write `MessageTransformMetricsTest`:
        - Counter increment: success, error, passthrough, deny.
        - Total count = sum of all.
        - Average/max/last transform time.
        - `resetMetrics()` zeroes all counters.
        - Reload success/failure counters.
        - Active spec count.
     2. **Test first:** Write `MessageTransformRuleTest.jmx*` tests:
        - `enableJmxMetrics = true` → MBean registered at expected `ObjectName`.
        - `enableJmxMetrics = false` → no MBean registered.
        - Reconfigure: `true` → `false` → MBean unregistered.
        - Counters increment during request processing.
     3. Implement `MessageTransformMetricsMXBean` interface.
     4. Implement `MessageTransformMetrics` with `LongAdder` fields.
     5. Wire MBean registration/unregistration in `configure()` / `shutdown()`.
     6. Wire counter increments into `handleRequest()` / `handleResponse()`:
        conditional on `enableJmxMetrics`.
     7. Run tests, verify all pass.
   - _Requirements covered:_ FR-002-14, S-002-33, S-002-34.
   - _Commands:_ `./gradlew :adapter-pingaccess:test`,
     `./gradlew spotlessApply check`
   - _Exit:_ JMX MBean works when enabled. Zero overhead when disabled.
     All counters correct.

### Phase 7 — Packaging, Thread Safety & Hardening (≤4 × 90 min)

10. **I10 — Shadow JAR verification + version guard** (≤90 min)
    - _Goal:_ Verify the shadow JAR is correct: contains adapter + core +
      all runtime deps, excludes PA SDK + Jackson + SLF4J + Jakarta. Verify
      `PaVersionGuard` initialization and version-check log. Verify JAR size
      < 5 MB.
    - _Preconditions:_ I9 complete (all production classes exist).
    - _Steps:_
      1. **Test first:** Write `ShadowJarTest` (integration test):
         - `jar tf` output contains `io/messagexform/pingaccess/` classes.
         - `jar tf` output contains `io/messagexform/core/` classes.
         - `jar tf` output contains JSLT, SnakeYAML, JSON Schema Validator.
         - `jar tf` output does NOT contain `com/pingidentity/pa/sdk/`.
         - `jar tf` output does NOT contain `com/fasterxml/jackson/` (PA-provided).
         - `jar tf` output DOES contain `io/messagexform/internal/jackson/`
           (core's relocated copy).
         - `jar tf` output does NOT contain `org/slf4j/` (PA-provided).
        - `jar tf` output does NOT contain `jakarta/validation/`.
         - JAR file size < 5 MB.
         - `META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor`
           exists.
      2. Build shadow JAR: `./gradlew :adapter-pingaccess:shadowJar`.
      3. Verify via `jar tf` and file size check.
         _Verification commands:_
         - `jar tf adapter-pingaccess/build/libs/adapter-pingaccess-*-SNAPSHOT.jar | grep -vE "io/messagexform|META-INF|snakeyaml|jslt|networknt"` (should return nothing or only empty directory entries).
         - `jar tf adapter-pingaccess/build/libs/adapter-pingaccess-*-SNAPSHOT.jar | grep "com/fasterxml/jackson"` (should return nothing — catches MR-JAR leakage under `META-INF/versions/`).
         - `jar tf adapter-pingaccess/build/libs/adapter-pingaccess-*-SNAPSHOT.jar | grep "META-INF/services/com.fasterxml.jackson"` (should return nothing — catches dangling service files).
      4. Verify `PaVersionGuard` runs during `configure()` and logs version
         info at INFO level.
         - Mismatch path (compiled vs runtime PA version) logs WARN with
           remediation message per ADR-0035.
    - _Requirements covered:_ FR-002-09, FR-002-10, NFR-002-02, S-002-24,
      S-002-36.
    - _Commands:_ `./gradlew :adapter-pingaccess:shadowJar`,
      `./gradlew :adapter-pingaccess:test`,
      `./gradlew spotlessApply check`
    - _Exit:_ Shadow JAR correct. No PA classes bundled. Size < 5 MB.

11. **I11 — Thread safety + performance validation** (≤90 min)
    - _Goal:_ Write concurrent test with 10 threads exercising
      `handleRequest()` + `handleResponse()` simultaneously. Verify no data
      corruption, no shared mutable state, independent results. Measure
      adapter overhead for < 64 KB body.
    - _Preconditions:_ I10 complete.
    - _Steps:_
      1. **Test first:** Write `MessageTransformRuleThreadSafetyTest`:
         - 10 threads × 100 requests each, different bodies/paths.
         - Assert: each thread gets correct transformation result.
         - Assert: no `ConcurrentModificationException` or data corruption.
         - Assert: `ExchangeProperty` values are independent per exchange.
      2. **Test first:** Write `PingAccessAdapterPerformanceTest`:
         - Transform a 64 KB JSON body 100 times.
         - Assert: average adapter overhead (wrap + apply, excluding engine
           time) < 10 ms.
      3. Run tests, verify all pass.
    - _Requirements covered:_ NFR-002-01, NFR-002-03, S-002-16, S-002-20.
    - _Commands:_ `./gradlew :adapter-pingaccess:test`,
      `./gradlew spotlessApply check`
    - _Exit:_ Thread safety verified. Performance budget met.

12. **I12 — Security validation + path traversal defense** (≤45 min)
    - _Goal:_ Implement path traversal validation in `configure()` for
      `specsDir` and `profilesDir` (reject `..` segments, log resolved path).
      Write negative tests.
    - _Preconditions:_ I11 complete.
    - _Steps:_
      1. **Test first:** Write `MessageTransformRuleTest.pathSecurity*` tests:
         - `specsDir = /opt/../etc/passwd` → `ValidationException`.
         - `specsDir = /opt/specs` → accepted.
         - `profilesDir` with `..` → `ValidationException`.
         - `specsDir` points to a regular file (not directory) →
           `ValidationException`.
         - `specsDir`/`profilesDir` exists but unreadable →
           `ValidationException`.
         - Resolved absolute path logged at INFO level.
      2. Implement path validation in `configure()`.
      3. Run tests, verify all pass.
    - _Requirements covered:_ FR-002-04 (security note).
    - _Commands:_ `./gradlew :adapter-pingaccess:test`,
      `./gradlew spotlessApply check`
    - _Exit:_ Path traversal rejected. Audit logging verified.


    > **ArchUnit validation** is a cross-cutting concern owned by Feature 009
    > (FR-009-12). The no-reflection, module-boundary, and SPI-layering rules
    > apply to all modules (including `adapter-pingaccess`) and are enforced
    > by the shared ArchUnit test suite. See FR-009-12 for the rule catalogue.

### Phase 8 — Quality Gate & Documentation (≤2 × 90 min)

13. **I13 — Full quality gate + scenario coverage audit** (≤45 min)
    - _Goal:_ Run the full quality gate across all modules. Update scenario
      coverage matrix. Verify no drift between spec and implementation.
    - _Preconditions:_ I12 complete.
    - _Steps:_
      1. Run `./gradlew --no-daemon spotlessApply check`.
      2. Verify all tests pass across `core`, `adapter-standalone`,
         `adapter-pingaccess`.
      3. Update `scenarios.md` coverage matrix — map each S-002-XX to
         its test class and method.
      4. Run drift gate checklist.
      5. Verify `./gradlew --no-daemon :adapter-pingaccess:compileJava`
         remains green with `-Xlint:all -Werror` (NFR-002-05).
    - _Requirements covered:_ All in-scope FRs
      (FR-002-01..11, FR-002-13, FR-002-14) and all NFRs (verification pass).
    - _Commands:_ `./gradlew --no-daemon spotlessApply check`,
      `./gradlew --no-daemon :adapter-pingaccess:compileJava`
    - _Exit:_ All tests green. Scenario coverage matrix complete. No drift.

14. **I14 — Documentation sync & roadmap update** (≤30 min)
    - _Goal:_ Update `roadmap.md`, `AGENTS.md` mirror, `knowledge-map.md`,
      `llms.txt`, and `_current-session.md` to reflect completed Feature 002
      adapter implementation.
    - _Preconditions:_ I13 complete.
    - _Steps:_
      1. Update `roadmap.md`: F002 → `✅ Complete` (FR-002-12 Docker E2E
         validated — see `e2e-results.md`).
      2. Sync `AGENTS.md` roadmap mirror.
      3. Update `knowledge-map.md` with new source files.
      4. Update `llms.txt` with adapter source files.
      5. Update `_current-session.md`.
      6. Commit all docs changes.
    - _Requirements covered:_ Documentation sync (Rule 19).
    - _Commands:_ N/A (documentation only).
    - _Exit:_ All docs consistent. FR-002-12 Docker E2E validated.

---

## Scenario Tracking

| Scenario ID | Increment / Task Reference | Notes |
|-------------|---------------------------|-------|
| S-002-01 | I2, I4a | Request body transform |
| S-002-02 | I3, I4a | Response body transform |
| S-002-03 | I4a | Bidirectional transform |
| S-002-04 | I3 | Header transform |
| S-002-05 | I3 | Status code transform |
| S-002-06 | I3 | URL rewrite |
| S-002-07 | I2 | Empty body |
| S-002-08 | I2 | Non-JSON body |
| S-002-09 | I4a | Profile matching |
| S-002-10 | I4a | No matching spec |
| S-002-11 | I7 | Error mode PASS_THROUGH |
| S-002-12 | I7 | Error mode DENY |
| S-002-13 | I6 | Session context in JSLT |
| S-002-14 | I6 | No identity (unauthenticated) |
| S-002-15 | I4a | Multiple specs loaded |
| S-002-16 | I11 | Large body (64 KB) |
| S-002-17 | I1 | Plugin configuration via admin UI |
| S-002-18 | I1, I4a | Invalid spec directory |
| S-002-19 | I4b | Plugin SPI registration |
| S-002-20 | I11 | Thread safety |
| S-002-21 | I4b | ExchangeProperty metadata |
| S-002-22 | I5 | Cookie access in JSLT |
| S-002-23 | I5 | Query param access in JSLT |
| S-002-24 | I10 | Shadow JAR correctness |
| S-002-25 | I6 | OAuth context in JSLT |
| S-002-26 | I6 | Session state in JSLT |
| S-002-27 | I2 | Prior rule URI rewrite |
| S-002-28 | I7 | DENY + handleResponse interaction |
| S-002-29 | I8 | Spec hot-reload (success) |
| S-002-30 | I8 | Spec hot-reload (failure) |
| S-002-31 | I8 | Concurrent reload during active transform |
| S-002-32 | I3 | Non-JSON response body |
| S-002-33 | I9 | JMX metrics opt-in |
| S-002-34 | I9 | JMX metrics disabled (default) |
| S-002-35 | I7 | PA-specific status codes passthrough (Constraint 9) |
| S-002-36 | I10 | Runtime PA version mismatch logs WARN remediation (ADR-0035) |
| S-002-09b | I4a | Profile matching negative case — no match passthrough |
| S-002-15b | I4a | Multiple specs reverse routing — Spec B triggered |
| S-002-33b | I9 | JMX metrics reset behavior |

## Analysis Gate

Run `docs/operations/analysis-gate-checklist.md` at two milestones:

- **Phase 3 gate (after I5):** Adapter bridge + basic rule lifecycle working.
  Verify core proxy cycle works end-to-end (wrap → transform → apply), SUCCESS
  and PASSTHROUGH paths correct. ~40% of scenarios should pass.
- **Phase 8 gate (after I14):** All code complete. Full scenario sweep, drift
  gate, coverage matrix, FR/NFR traceability tables filled in.

## Exit Criteria

- [ ] All increments (I1–I14) completed and checked off
- [ ] Quality gate passes (`./gradlew --no-daemon spotlessApply check`)
- [ ] All scenarios defined in `scenarios.md` have corresponding test methods
- [ ] Scenario coverage matrix in `scenarios.md` has no uncovered FRs/NFRs
- [ ] Shadow JAR < 5 MB, no PA SDK classes
- [ ] Thread safety test passes with 10 concurrent threads
- [ ] Implementation Drift Gate report attached
- [ ] Open questions resolved and removed from `open-questions.md`
- [ ] Documentation synced (roadmap, knowledge-map, AGENTS.md, llms.txt)

## Intent Log

_To be filled during implementation._

- **I1:** …
- **I2:** …
- **I3:** …
- **I4a:** …
- **I4b:** …
- **I5:** …
- **I6:** …
- **I7:** …
- **I8:** …
- **I9:** …
- **I10:** …
- **I11:** …
- **I12:** …
- **I13:** …
- **I14:** …

## Follow-ups / Backlog

- [x] **FR-002-12: Docker E2E test script** — ✅ Complete.
      50/50 tests pass against PA 9.0.1.0. See `e2e-results.md`.
      22 scenarios validated E2E, 6 specs, profile routing, 19 test groups.
- [ ] **E2E: OAuth/identity scenarios** — S-002-13, S-002-25, S-002-26.
      Requires authenticated PA session. Options: (a) PingFederate/PingAM as
      IdP in Docker Compose, (b) lightweight Python OIDC stub (mock token
      introspection + session attributes). Consider [mock-oauth2-server](https://github.com/navikt/mock-oauth2-server).
- [ ] **E2E: Hot-reload scenarios** — S-002-29, S-002-30, S-002-31.
      Requires file-system mutation during live E2E run (modify spec YAML
      while PA is running, verify reload behavior).
- [ ] **E2E: JMX metrics scenarios** — S-002-33, S-002-34.
      Requires JMX client connection to PA JVM (expose JMX port in Docker,
      use `jmxterm` or `jmx_exporter` for assertion).
- [ ] **E2E: Multi-rule chain** — S-002-27.
      Requires two PA rules in sequence (first rewrites URI, second reads it).
- [ ] **Compressed body support** (Constraint 10) — Decompress `gzip`/`deflate`/`br`
      response bodies before JSON parsing. Requires adding `java.util.zip` handling.
- [ ] **SessionContextFilter** — Selective `$session` field whitelisting for
      multi-tenant spec authoring. Follow-up if needed.
- [ ] **PA-native error handler integration** — Add `ErrorHandlerUtil`
      configuration fields for PA HTML template-based error pages alongside
      RFC 9457. Follow-up if requested.

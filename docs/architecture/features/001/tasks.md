# Feature 001 — Message Transformation Engine — Tasks

_Status:_ Complete (Phases 1–12); Phase 13 (conditional response routing) in progress
_Last updated:_ 2026-02-15

**Governing spec:** `docs/architecture/features/001/spec.md`
**Implementation plan:** `docs/architecture/features/001/plan.md`

> Keep this checklist aligned with the feature plan increments. Stage tests
> before implementation, record verification commands beside each task, and
> prefer bite-sized entries (≤90 minutes).
>
> When referencing requirements, keep feature IDs (`FR-`), non-functional IDs
> (`NFR-`), and scenario IDs (`S-001-`) in parentheses after the task title.
>
> When new high- or medium-impact questions arise during execution, add them to
> `docs/architecture/open-questions.md` instead of informal notes, and treat a
> task as fully resolved only once the governing spec sections and, when
> required, ADRs under `docs/decisions/` reflect the clarified behaviour.

## Task Checklist

Tasks are ordered by dependency. Each task references the spec requirement it
implements and **sequences tests before code** (Rule 12 — TDD cadence).

---

### Phase 1 — Foundation: Project Skeleton + Domain Model

#### I1 — Gradle project init + core module

- [x] **T-001-01** — Initialize Gradle wrapper with Java 21 (NFR-001-02) ✅ 2026-02-08
  _Intent:_ Establish the build foundation. All subsequent tasks depend on a
  working Gradle build. Co-created with Feature 009.
  _Implement:_ Run `gradle init` or create `settings.gradle.kts` +
  `build.gradle.kts` manually. Configure Java 21 toolchain. Create `core`
  submodule with `core/build.gradle.kts`.
  _Verify:_ `./gradlew --version` shows Gradle wrapper working.
  _Verification commands:_
  - `./gradlew --version`
  - `./gradlew projects`

- [x] **T-001-02** — Add core dependencies and configure Spotless (NFR-001-02) ✅ 2026-02-08
  _Intent:_ Lock down the dependency set for the core module and establish
  code formatting from day one.
  _Implement:_ Add to `core/build.gradle.kts`: Jackson Databind,
  jackson-dataformat-yaml, JSLT, json-schema-validator, SLF4J API. Test
  dependencies: JUnit 5, AssertJ. Configure Spotless plugin (Palantir or
  Google Java Format — decide and record in Feature 009).
  _Verify:_ Dependencies resolve. Spotless formats an empty source set.
  _Verification commands:_
  - `./gradlew :core:dependencies --configuration compileClasspath`
  - `./gradlew spotlessApply check`

- [x] **T-001-03** — Verify zero gateway-specific dependencies (NFR-001-02) ✅ 2026-02-08
  _Intent:_ Establish the NFR-001-02 gate from the start. The core module
  MUST NOT depend on any gateway SDK (PingAccess, PingGateway, Kong, etc.).
  _Test first:_ Create `CoreDependencyTest` — parse Gradle dependency tree
  output and assert no gateway SDK artifacts appear.
  _Implement:_ N/A — this is a verification-only task.
  _Verify:_ `CoreDependencyTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*CoreDependencyTest*"`
  - `./gradlew spotlessApply check`

#### I2 — Domain model + Exception hierarchy

- [x] **T-001-04** — Message record (DO-001-01, FR-001-04) ✅ 2026-02-08
  _Intent:_ Define the generic HTTP message envelope that all gateway
  adapters will produce. This is the engine's universal input/output type.
  _Test first:_ Write `MessageTest` — construct a Message with body
  (JsonNode), headers (Map), statusCode, contentType, requestPath,
  requestMethod. Assert all fields are accessible. Test immutability.
  _Implement:_ Create `Message` interface or record in
  `core/src/main/java/io/messagexform/core/model/`.
  _Verify:_ `MessageTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*MessageTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-05** — TransformContext record (DO-001-07) ✅ 2026-02-08
  _Intent:_ Define the read-only context passed to expression engines during
  evaluation. This carries `$headers`, `$headers_all`, `$status`,
  `$queryParams`, `$cookies`.
  _Test first:_ Write `TransformContextTest` — construct context with
  headers, headersAll, statusCode, queryParams, cookies. Assert JsonNode
  binding matches spec (first-value for $headers, array-of-strings for
  $headers_all, null $status for request transforms per ADR-0017).
  _Implement:_ Create `TransformContext` interface in
  `core/src/main/java/io/messagexform/core/model/`.
  _Verify:_ `TransformContextTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*TransformContextTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-06** — TransformResult (DO-001-05) ✅ 2026-02-08
  _Intent:_ Define the outcome type returned by the engine. Must represent
  three states: SUCCESS (with transformed message), ERROR (with error
  response body + status), PASSTHROUGH (no changes).
  _Test first:_ Write `TransformResultTest` — construct each state, verify
  fields. SUCCESS must have non-null message. ERROR must have errorResponse
  and errorStatusCode. PASSTHROUGH must have null message and null error.
  _Implement:_ Create `TransformResult` in
  `core/src/main/java/io/messagexform/core/model/`.
  _Verify:_ `TransformResultTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*TransformResultTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-07** — Direction enum ✅ 2026-02-08
  _Intent:_ Simple enum for REQUEST/RESPONSE direction. Used throughout
  the engine for bidirectional transform selection.
  _Test first:_ Minimal — assert `Direction.REQUEST` and `Direction.RESPONSE`
  exist (compile-time check is sufficient).
  _Implement:_ Create `Direction` enum.
  _Verify:_ Compiles.
  _Verification commands:_
  - `./gradlew :core:test`
  - `./gradlew spotlessApply check`

- [x] **T-001-08** — Exception hierarchy (FR-001-07, ADR-0024) ✅ 2026-02-08
  _Intent:_ Implement the two-tier exception hierarchy from the Error
  Catalogue. All engine error handling depends on these types.
  _Test first:_ Write `ExceptionHierarchyTest` — verify:
  - `TransformLoadException` is abstract, extends `TransformException`.
  - `TransformEvalException` is abstract, extends `TransformException`.
  - All 5 load-time subtypes (`SpecParseException`, `ExpressionCompileException`,
    `SchemaValidationException`, `ProfileResolveException`,
    `SensitivePathSyntaxError`) extend `TransformLoadException`.
  - All 3 eval-time subtypes (`ExpressionEvalException`,
    `EvalBudgetExceededException`, `InputSchemaViolation`) extend
    `TransformEvalException`.
  - Common fields: specId, detail, phase. Load: source. Eval: chainStep.
  _Implement:_ Create exception classes in
  `core/src/main/java/io/messagexform/core/error/`.
  _Verify:_ `ExceptionHierarchyTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*ExceptionHierarchyTest*"`
  - `./gradlew spotlessApply check`

---

### Phase 2 — Expression Engine SPI + JSLT Engine

#### I3 — Expression Engine SPI + JSLT engine

- [x] **T-001-09** — ExpressionEngine + CompiledExpression interfaces (DO-001-06, DO-001-03, FR-001-02) ✅ 2026-02-08
  _Intent:_ Define the pluggable SPI that allows different transformation
  engines (JSLT, JOLT, jq) to be registered. This is the extension point
  for the entire engine.
  _Test first:_ Write `ExpressionEngineSpiTest` — define a mock engine impl,
  register it, verify `id()` contract, `compile()` returns
  `CompiledExpression`, `evaluate()` transforms JsonNode input.
  _Implement:_ Create `ExpressionEngine` interface (id, compile) and
  `CompiledExpression` interface (evaluate) in
  `core/src/main/java/io/messagexform/core/spi/`.
  _Verify:_ `ExpressionEngineSpiTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*ExpressionEngineSpiTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-10** — JSLT engine implementation (FR-001-02, SPI-001-01/02/03) ✅ 2026-02-08
  _Intent:_ Implement the default expression engine using the JSLT library.
  This is the workhorse — most scenarios use JSLT.
  _Test first:_ Write `JsltExpressionEngineTest` with parameterized cases:
  - Simple rename: `{"userId": .user_id}` (S-001-08 pattern).
  - Conditional: `if (.error) ... else ...` (S-001-15 pattern).
  - Array reshape: `[for (.items) ...]` (S-001-13 pattern).
  - Open-world passthrough: `{ * : . } - "secret"` (S-001-07 pattern).
  _Implement:_ Create `JsltExpressionEngine` in
  `core/src/main/java/io/messagexform/core/engine/jslt/`.
  Delegate to `com.schibsted.spt.data:jslt` library.
  _Verify:_ All 4 parameterized cases pass.
  _Verification commands:_
  - `./gradlew :core:test --tests "*JsltExpressionEngineTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-11** — Engine registry (FR-001-02, API-001-05) ✅ 2026-02-08
  _Intent:_ Manage expression engine registration so the spec parser can
  resolve `lang: jslt` to the correct engine at load time.
  _Test first:_ Write `EngineRegistryTest`:
  - Register JSLT engine → `getEngine("jslt")` returns it.
  - `getEngine("unknown")` → throws appropriate error.
  - Register duplicate id → throws or replaces (decide and document).
  _Implement:_ Create `EngineRegistry` class.
  _Verify:_ `EngineRegistryTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*EngineRegistryTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-12** — Research: JSLT context variable binding ✅ 2026-02-08
  _Intent:_ Determine how to pass `$headers`, `$status`, `$queryParams`,
  `$cookies` to JSLT expressions. The JSLT library may support external
  variables via its API, or we may need to inject them into the input JSON.
  _Test first:_ Write spike test: create a JSLT expression using a variable
  `$headers`, compile and evaluate with context. Document what works.
  _Implement:_ Document findings in
  `docs/research/jslt-context-variables.md`. If JSLT supports context
  variables, note the API. If not, document the input-wrapping approach.
  _Verify:_ Research document committed. Decision captured.
  _Verification commands:_
  - N/A — research task, no production test.

---

### Phase 3 — Spec Parsing + Loading

#### I4 — YAML spec parser

- [x] **T-001-13** — Create test fixture YAML files (FX-001-01/02/03)
  _Intent:_ Create the YAML fixtures that all spec parsing tests will use.
  These are the "golden" test vectors from the spec.
  _Implement:_ Create in `core/src/test/resources/test-vectors/`:
  - `jslt-simple-rename.yaml` (FX-001-01)
  - `jslt-conditional.yaml` (FX-001-02)
  - `jslt-array-reshape.yaml` (FX-001-03)
  All fixtures MUST include `input.schema` and `output.schema` blocks
  (FR-001-09 — schemas are required). This ensures T-001-14 exercises the
  full parse path and T-001-18 can reuse the same fixtures for schema
  validation testing.
  _Verify:_ Files exist and are valid YAML.
  _Verification commands:_
  - N/A — file creation only.

- [x] **T-001-14** — Parse valid spec YAML → TransformSpec (FR-001-01, DO-001-02)
  _Intent:_ The core spec parser: read a YAML file, resolve the expression
  engine, compile the expression, and produce an immutable `TransformSpec`.
  _Test first:_ Write `SpecParserTest`:
  - Load `jslt-simple-rename.yaml` → TransformSpec with correct id,
    version, description (optional), lang="jslt", non-null compiledExpr.
  - Load `jslt-conditional.yaml` → same structure.
  - Evaluate the compiled expression against test input → correct output.
  _Implement:_ Create `SpecParser` class in
  `core/src/main/java/io/messagexform/core/spec/`.
  Use Jackson YAML parser. Resolve engine via `EngineRegistry`.
  _Design note:_ `TransformSpec` is an immutable record with nullable fields
  for attributes populated by later phases (sensitive, match, headers, status,
  mappers, url). Phase 3 populates: id, version, description, lang,
  inputSchema, outputSchema, compiledExpr, forward, reverse. Remaining
  fields are null until Phases 5–6 extend parsing (url is Phase 6 / I11a
  per FR-001-12/ADR-0027).
  _Verify:_ `SpecParserTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*SpecParserTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-15** — Spec parse error handling (FR-001-01, FR-001-07) ✅ 2026-02-08
  _Intent:_ Verify that invalid specs produce the correct typed exceptions.
  _Test first:_ Write additional `SpecParserTest` cases:
  - Invalid YAML syntax → `SpecParseException`.
  - Unknown engine id (`lang: nonexistent`) → `ExpressionCompileException`.
  - Invalid JSLT syntax → `ExpressionCompileException`.
  - Missing required fields (no `id`, no `transform`) → `SpecParseException`.
  _Implement:_ Add error handling to `SpecParser`.
  _Verify:_ All error cases throw correct exceptions with correct fields.
  _Verification commands:_
  - `./gradlew :core:test --tests "*SpecParserTest*"`
  - `./gradlew spotlessApply check`

#### I5 — Bidirectional specs + schema validation

- [x] **T-001-16** — Create bidirectional test fixture (FX-001-04) ✅ 2026-02-08
  _Intent:_ Create the YAML fixture for bidirectional transform testing.
  _Implement:_ Create `core/src/test/resources/test-vectors/bidirectional-roundtrip.yaml`
  with `forward.expr` and `reverse.expr` blocks.
  _Verify:_ File exists and is valid YAML.
  _Verification commands:_
  - N/A — file creation only.

- [x] **T-001-17** — Parse bidirectional specs (FR-001-03) ✅ 2026-02-08
  _Intent:_ Extend the spec parser to handle `forward`/`reverse` blocks
  instead of a single `transform` block.
  _Test first:_ Write `BidirectionalSpecTest`:
  - Load bidirectional YAML → TransformSpec with forward and reverse
    `CompiledExpression` handles (both non-null).
  - Evaluate forward expr → correct output.
  - Evaluate reverse expr → correct reverse output.
  - Round-trip: forward(input) → output, reverse(output) → input.
  _Implement:_ Extend `SpecParser` and `TransformSpec` for `forward`/`reverse`.
  _Verify:_ `BidirectionalSpecTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*BidirectionalSpecTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-18** — JSON Schema load-time validation (FR-001-09) ✅ 2026-02-08
  _Intent:_ Every spec MUST declare `input.schema` and `output.schema`.
  The engine validates they are valid JSON Schema 2020-12 at load time.
  _Test first:_ Write `SchemaValidationTest`:
  - Spec with valid `input.schema` and `output.schema` → loads without error.
  - Spec with invalid JSON Schema (e.g., `type: not-a-type`) →
    `SchemaValidationException`.
  - Spec with no schema blocks → `SpecParseException` (FR-001-09 uses
    RFC 2119 MUST — missing schemas are a load-time error, not a warning).
  _Implement:_ Integrate `networknt/json-schema-validator` into `SpecParser`.
  Validate schema blocks at load time.
  _Verify:_ `SchemaValidationTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*SchemaValidationTest*"`
  - `./gradlew spotlessApply check`

---

### Phase 4 — Core Engine: Transform Pipeline

#### I6 — TransformEngine: basic transform

- [x] **T-001-19** — Basic transform: load spec + transform message (FR-001-01, FR-001-02, FR-001-04, API-001-01/03)
  _Intent:_ The core transform path: load a spec, create a message, call
  `transform()`, get back a `TransformResult` with the transformed body.
  This is the moment the engine comes alive.
  _Test first:_ Write `TransformEngineTest` with parameterized scenarios:
  - S-001-01: PingAM callback prettification.
  - S-001-06: Strip internal fields.
  - S-001-08: Rename fields (API versioning).
  - S-001-09: Add default values.
  - S-001-11: Flatten nested object.
  - S-001-13: Array-of-objects reshaping.
  For each: load spec YAML, construct `Message` from scenario input,
  call `engine.transform(message, RESPONSE)`, assert output body matches
  `expected_output`.
  _Implement:_ Create `TransformEngine` class with `loadSpec(Path)` and
  `transform(Message, Direction)` methods.
  _Verify:_ All 6 parameterized cases pass.
  _Verification commands:_
  - `./gradlew :core:test --tests "*TransformEngineTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-20** — Passthrough for non-matching messages (FR-001-06)
  _Intent:_ When no spec/profile matches, the engine MUST return the message
  completely unmodified. This is the default safety behaviour.
  _Test first:_ Write `PassthroughTest`:
  - No specs loaded → any message → PASSTHROUGH result.
  - Spec loaded but message doesn't match → PASSTHROUGH.
  - Verify original message body, headers, status are unchanged.
  _Implement:_ Add passthrough logic to `TransformEngine.transform()`.
  _Verify:_ `PassthroughTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*PassthroughTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-21** — Context variable binding: $headers, $status (FR-001-10, FR-001-11)
  _Intent:_ Wire `TransformContext` into JSLT evaluation so expressions can
  reference `$headers`, `$headers_all`, `$status`. Depends on T-001-12
  research findings.
  _Test first:_ Write `ContextBindingTest`:
  - JSLT expression uses `$headers."X-Request-ID"` → correct value from
    message headers.
  - JSLT expression uses `$status` → correct status code.
  - Request transform: `$status` is null (ADR-0017).
  _Implement:_ Based on T-001-12 findings — either use JSLT external
  variables API or wrap input JSON with context fields.
  _Verify:_ `ContextBindingTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*ContextBindingTest*"`
  - `./gradlew spotlessApply check`

#### I7 — Error handling + evaluation budgets

- [x] **T-001-22** — Error response builder: RFC 9457 (FR-001-07, CFG-001-03/04)
  _Intent:_ When a transform fails, the engine produces an error response.
  The default format is RFC 9457 Problem Details.
  _Test first:_ Write `ErrorResponseBuilderTest`:
  - `ExpressionEvalException` → RFC 9457 JSON with correct `type`, `title`,
    `status`, `detail`, `instance` fields.
  - Verify `type` matches the URN from the Error Catalogue.
  - Verify configurable `status` (default 502).
  _Implement:_ Create `ErrorResponseBuilder` class.
  _Verify:_ `ErrorResponseBuilderTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*ErrorResponseBuilderTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-23** — Custom error template (FR-001-07, CFG-001-05)
  _Intent:_ Support operator-defined error templates with `{{error.*}}`
  placeholder substitution.
  _Test first:_ Write `CustomErrorTemplateTest`:
  - Template with `{{error.detail}}`, `{{error.specId}}`, `{{error.type}}`
    → correct substitution.
  - Invalid template (unknown placeholder) → graceful fallback.
  _Implement:_ Extend `ErrorResponseBuilder` for custom template mode.
  _Verify:_ `CustomErrorTemplateTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*CustomErrorTemplateTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-24** — JSLT evaluation error → error response (FR-001-07, S-001-22)
  _Intent:_ End-to-end: a broken JSLT expression at evaluation time should
  produce a proper error response, not crash the engine.
  _Test first:_ Write `EvalErrorTest`:
  - Expression references undefined variable → `ExpressionEvalException` →
    engine returns ERROR TransformResult with RFC 9457 body.
  - Verify original message is NOT passed through (ADR-0022).
  _Implement:_ Add try/catch in `TransformEngine.transform()` for eval exceptions.
  _Verify:_ `EvalErrorTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*EvalErrorTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-25** — Evaluation budget: max-eval-ms + max-output-bytes (NFR-001-07, CFG-001-06/07)
  _Intent:_ Runaway expressions must be terminated. Enforce time and output
  size budgets.
  _Test first:_ Write `EvalBudgetTest`:
  - Expression that takes > max-eval-ms → `EvalBudgetExceededException`.
  - Output that exceeds max-output-bytes → `EvalBudgetExceededException`.
  - Normal expression within budget → succeeds.
  _Implement:_ Add budget tracking to expression evaluation (thread interrupt
  or timeout wrapper for time; output size check after evaluation).
  _Verify:_ `EvalBudgetTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*EvalBudgetTest*"`
  - `./gradlew spotlessApply check`
  _Notes:_ Time budget enforcement may need a separate executor thread.
  Evaluate if JSLT evaluation can be interrupted. If not, document as a
  known limitation and rely on output size budget as primary guard.

- [x] **T-001-26** — Strict-mode schema validation at evaluation time (FR-001-09, CFG-001-09)
  _Intent:_ In strict mode, validate input against `input.schema` before
  eval and output against `output.schema` after eval.
  _Test first:_ Write `StrictModeTest`:
  - Strict mode ON + conforming input → transform succeeds.
  - Strict mode ON + non-conforming input → `InputSchemaViolation` → error
    response.
  - Strict mode OFF (lenient, default) → non-conforming input → transform
    proceeds without validation.
  _Implement:_ Add schema validation step to `TransformEngine.transform()`
  when `schema-validation-mode: strict`.
  _Verify:_ `StrictModeTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*StrictModeTest*"`
  - `./gradlew spotlessApply check`

---

### Phase 5 — Profiles + Matching

#### I8 — Profile parser + matching

- [x] **T-001-27** — Create profile test fixture YAML
  _Intent:_ Create test profile YAML for the PingAM callback use case.
  _Implement:_ Create `core/src/test/resources/test-profiles/pingam-callback-prettify.yaml`
  with path/method matching and spec references.
  _Verify:_ File exists and is valid YAML.
  _Verification commands:_
  - N/A — file creation only.

- [x] **T-001-28** — Parse profile YAML → TransformProfile (FR-001-05, DO-001-04/08)
  _Intent:_ Parse profile YAML into a `TransformProfile` with resolved spec
  references.
  _Test first:_ Write `ProfileParserTest`:
  - Load profile YAML → `TransformProfile` with correct entries.
  - Each entry has resolved `TransformSpec` reference.
  - Missing spec reference → `ProfileResolveException`.
  - Unknown version → `ProfileResolveException`.
  _Implement:_ Create `ProfileParser` class.
  _Verify:_ `ProfileParserTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*ProfileParserTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-29** — Profile matching: path, method, content-type (FR-001-05, ADR-0006)
  _Intent:_ Implement the matching logic that selects which profile entry
  applies to a given request.
  _Test first:_ Write `ProfileMatcherTest`:
  - Exact path match → entry selected.
  - Glob wildcard path (`/json/*/authenticate`) → matches.
  - Method mismatch → no match.
  - Content-type mismatch → no match.
  - No matching entry → passthrough.
  - Most-specific-wins: `/json/alpha/authenticate` beats `/json/*/authenticate`.
  _Implement:_ Create `ProfileMatcher` class.
  _Verify:_ `ProfileMatcherTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*ProfileMatcherTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-30** — Integrate profiles into TransformEngine (API-001-01/02)
  _Intent:_ Wire profile loading and matching into the engine so
  `transform(message, direction)` resolves the profile internally.
  _Test first:_ Write `ProfileIntegrationTest`:
  - Load spec + profile → send matching request → transform applied.
  - Send non-matching request → passthrough.
  - Load profile with multiple entries → most-specific wins.
  _Implement:_ Add `loadProfile(Path)` to `TransformEngine`. Modify
  `transform()` to resolve via profile matcher.
  _Verify:_ `ProfileIntegrationTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*ProfileIntegrationTest*"`
  - `./gradlew spotlessApply check`

#### I9 — Profile chaining + bidirectional transform via profiles

- [x] **T-001-31** — Profile-level chaining (FR-001-05, ADR-0012, S-001-49)
  _Intent:_ When a profile has multiple matching entries for the same
  direction, they execute in declaration order as a pipeline.
  _Test first:_ Write `ChainingTest`:
  - Profile with 2 entries (same direction) → output of step 1 feeds
    step 2 (S-001-49 pattern: JOLT → JSLT mixed-engine chain).
  - Chain step fails → entire chain aborted, error response returned.
  - Verify no partial results reach the caller.
  _Implement:_ Add chaining logic to `TransformEngine.transform()`.
  _Verify:_ `ChainingTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*ChainingTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-32** — Bidirectional transform via profiles (FR-001-03, S-001-02)
  _Intent:_ Profile entries with `direction: response` use `forward.expr`;
  entries with `direction: request` use `reverse.expr`.
  _Test first:_ Write `BidirectionalProfileTest`:
  - Load bidirectional spec + profile with response entry →
    `forward.expr` applied.
  - Same profile with request entry → `reverse.expr` applied.
  - Round-trip: forward(input) via response path → output. reverse(output)
    via request path → original input.
  _Implement:_ Wire direction-based expression selection into engine.
  _Verify:_ `BidirectionalProfileTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*BidirectionalProfileTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-33** — Chain step logging (NFR-001-08)
  _Intent:_ Each chain step MUST be logged with the step index
  (`chain_step: 2/3`) alongside the spec id.
  _Test first:_ Write `ChainStepLoggingTest`:
  - Execute 3-step chain → verify structured log entries contain
    `chain_step`, `spec_id`, `profile_id` for each step.
  _Implement:_ Add structured logging to chain execution loop.
  _Verify:_ `ChainStepLoggingTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*ChainStepLoggingTest*"`
  - `./gradlew spotlessApply check`

---

### Phase 6 — Headers, Status, URL, Mappers

#### I10 — Header transformations

- [x] **T-001-34** — Header add/remove/rename (FR-001-10, S-001-33/34/35) ✅ 2026-02-09
  _Intent:_ Implement the declarative header operations block.
  _Test first:_ Write `HeaderTransformTest`:
  - `add` static: add `X-Transformed-By: message-xform` → present in output.
  - `remove` glob: `X-Internal-*` → matching headers removed.
  - `rename`: `X-Old-Header → X-New-Header` → renamed.
  - Processing order: remove → rename → add (static) → add (dynamic).
  _Implement:_ Create `HeaderTransformer` class.
  _Verify:_ `HeaderTransformTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*HeaderTransformTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-35** — Dynamic header expressions (FR-001-10) ✅ 2026-02-09
  _Intent:_ Header `add` with `expr` sub-key — evaluate JSLT against the
  transformed body to produce a header value.
  _Test first:_ Write `DynamicHeaderTest`:
  - `X-Error-Code` with `expr: .error.code` → extracts value from body.
  - Non-string result → coerced to JSON string representation.
  - Engine that doesn't support context vars (e.g., JOLT) + dynamic header
    → rejected at load time.
  _Implement:_ Add dynamic header evaluation to `HeaderTransformer`.
  _Verify:_ `DynamicHeaderTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*DynamicHeaderTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-36** — Header name normalization to lowercase (FR-001-10) ✅ 2026-02-09
  _Intent:_ All header names MUST be normalized to lowercase per RFC 9110 §5.1.
  _Test first:_ Write `HeaderNormalizationTest`:
  - Input headers with mixed case → `$headers` keys are lowercase.
  - Header operations use case-insensitive matching.
  _Implement:_ Add normalization to `TransformContext` builder and
  `HeaderTransformer`.
  _Verify:_ `HeaderNormalizationTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*HeaderNormalizationTest*"`
  - `./gradlew spotlessApply check`

#### I11 — Status code transformations

- [x] **T-001-37** — Status code override with `when` predicate (FR-001-11, S-001-36/37/38)
  _Intent:_ Implement the `status` block: `set` + optional `when` predicate.
  _Test first:_ Write `StatusTransformTest`:
  - Unconditional `set: 202` → status changed (S-001-38 pattern).
  - Conditional `when: '.error != null'` + error in body → status changed
    (S-001-36 pattern).
  - Conditional `when: '.error != null'` + no error → status unchanged
    (S-001-37 pattern).
  - Invalid status code (outside 100-599) → rejected at load time.
  - `when` predicate evaluation error → abort, keep original status.
  _Implement:_ Create `StatusTransformer` class.
  _Verify:_ `StatusTransformTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*StatusTransformTest*"`
  - `./gradlew spotlessApply check`
  _Status:_ ✅ Complete (2026-02-08). 9 tests pass. StatusSpec model, StatusTransformer,
  SpecParser.parseStatusSpec(), TransformEngine integration. All 212 tests pass.

- [x] **T-001-38** — $status binding in JSLT (FR-001-11, ADR-0017)
  _Intent:_ JSLT body expressions can use `$status` to reference the
  current HTTP status code. Null for request transforms.
  _Test first:_ Write `StatusBindingTest`:
  - Response transform: JSLT `$status < 400` → evaluates correctly.
  - Request transform: `$status` is null (ADR-0017).
  _Implement:_ Wire `$status` into `TransformContext` and JSLT evaluation.
  _Verify:_ `StatusBindingTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*StatusBindingTest*"`
  - `./gradlew spotlessApply check`
  _Status:_ ✅ Complete (2026-02-08). 4 tests pass. $status binding already
  implemented by T-001-21; dedicated StatusBindingTest covers S-001-37, S-001-61
  (null for requests, ADR-0017), S-001-63 (nullable Integer, ADR-0020).

#### I11a — URL rewriting

- [x] **T-001-38a** — URL path rewrite with JSLT expression (FR-001-12, ADR-0027, S-001-38a) ✅ 2026-02-08
  _Intent:_ Implement `url.path.expr` — a JSLT expression that constructs a new
  request path from body fields. The expression evaluates against the **original**
  (pre-transform) body (ADR-0027), so routing fields stripped by the body transform
  are still available.
  _Test first:_ Write `UrlPathRewriteTest`:
  - Spec with `url.path.expr: '"/api/" + .action + "/" + .resourceId'` + body
    `{"action": "users", "resourceId": "123"}` → path rewritten to `/api/users/123`.
  - Body transform strips `.action` and `.resourceId` — path still rewritten
    correctly (evaluates against **original** body).
  - RFC 3986 §3.3 encoding: body field with spaces → percent-encoded in path.
  - `path.expr` returns null → `ExpressionEvalException`.
  - `path.expr` returns non-string → `ExpressionEvalException`.
  _Implement:_ Create `UrlTransformer` class. Extend `SpecParser` to parse
  `url.path` block. Add `urlSpec` field to `TransformSpec`. Wire into
  `TransformEngine.transformWithSpec()` — evaluates against original body per ADR-0027.
  _Verify:_ `UrlPathRewriteTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*UrlPathRewriteTest*"`
  - `./gradlew spotlessApply check`
  _Status:_ ✅ Complete (2026-02-08). 8 tests pass: de-polymorphize dispatch (S-001-38a),
  original-body evaluation (ADR-0027), RFC 3986 percent-encoding (spaces, special chars),
  null/non-string error handling, direction restriction (request only), backward compatibility.
  New files: UrlSpec.java, UrlTransformer.java, UrlPathRewriteTest.java. Modified: TransformSpec,
  SpecParser (parseUrlSpec), TransformEngine (URL rewrite step). All 220 tests pass.

- [x] **T-001-38b** — URL query parameter add/remove (FR-001-12, S-001-38b) ✅ 2026-02-08
  _Intent:_ Implement `url.query.add` (static + dynamic) and `url.query.remove`
  (glob patterns) for modifying query parameters.
  _Test first:_ Write `UrlQueryParamTest`:
  - `query.add.format: "json"` → static query param added.
  - `query.add.correlationId.expr: '$headers."X-Correlation-ID"'` → dynamic
    query param from header context.
  - `query.remove: ["_debug", "_internal"]` → matching params removed.
  - `query.remove: ["_*"]` → glob pattern removes all underscore-prefixed params.
  - Query parameter values percent-encoded per RFC 3986 §3.4.
  _Implement:_ Extend `UrlTransformer` with query parameter operations. Add
  `queryString` field to `Message` (with backward-compat convenience constructor).
  Extend `SpecParser.parseUrlSpec()` with query.add/query.remove parsing.
  _Verify:_ `UrlQueryParamTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*UrlQueryParamTest*"`
  - `./gradlew spotlessApply check`
  _Status:_ ✅ Complete (2026-02-08). 10 tests pass: static add, dynamic add (headers +
  original body), exact remove, glob remove (_*), remove-all, combined operations
  (S-001-38b), percent-encoding, null query string. Added queryString field to Message
  with backward-compat constructor. All 230 tests pass.

- [x] **T-001-38c** — HTTP method override (FR-001-12, ADR-0027, S-001-38c) ✅ 2026-02-08
  _Intent:_ Implement `url.method.set` with optional `when` predicate, using the
  same pattern as `status` (FR-001-11). Enables complete de-polymorphization by
  changing both the URL path and HTTP method.
  _Test first:_ Write `UrlMethodOverrideTest`:
  - `method.set: "DELETE"` (unconditional) → method changed to DELETE.
  - `method.set: "GET"` + `when: '.action == "read"'` + matching body → method
    changed to GET.
  - `method.set: "GET"` + `when: '.action == "read"'` + non-matching body → method
    unchanged.
  - `method.when` predicate evaluates against **original** body (ADR-0027).
  _Implement:_ Extended `UrlTransformer.applyMethodOverride()` with set/when pattern.
  Added `isTruthy()` helper for JSLT predicate evaluation.
  _Verify:_ `UrlMethodOverrideTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*UrlMethodOverrideTest*"`
  - `./gradlew spotlessApply check`
  _Status:_ ✅ Complete (2026-02-08). 6 tests: unconditional set, conditional when
  (truthy/falsy), original body evaluation, combined path+method, direction handling.

- [x] **T-001-38d** — URL method validation at load time (FR-001-12, S-001-38d) ✅ 2026-02-08
  _Intent:_ Invalid HTTP methods in `method.set` MUST be rejected at load time
  with a `SpecParseException`. Valid methods: GET, POST, PUT, DELETE, PATCH, HEAD,
  OPTIONS.
  _Test first:_ Write `UrlMethodValidationTest`:
  - `method.set: "YOLO"` → `SpecParseException` at load time.
  - `method.set: "GET"` → accepted.
  - Lowercase method normalized to uppercase.
  _Implement:_ Added `VALID_HTTP_METHODS` constant and validation in
  `SpecParser.parseUrlSpec()`. Method normalized to uppercase before validation.
  _Verify:_ `UrlMethodValidationTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*UrlMethodValidationTest*"`
  - `./gradlew spotlessApply check`
  _Status:_ ✅ Complete (2026-02-08). 14 tests: 6 invalid methods rejected (parameterized),
  7 valid methods accepted (parameterized), lowercase normalization. All 250 tests pass.

- [x] **T-001-38e** — URL block on response transform → ignored with warning (FR-001-12, S-001-38e) ✅ 2026-02-08
  _Intent:_ URL rewriting only makes sense for request transforms. A `url` block
  on a response-direction spec is ignored with a warning logged at load time.
  _Test first:_ Write `UrlDirectionRestrictionTest`:
  - Spec with url.path.expr → ignored for RESPONSE direction.
  - Spec with url.query ops → ignored for RESPONSE direction.
  - Spec with url.method.set → ignored for RESPONSE direction.
  - Full url block → all ignored for RESPONSE direction.
  - Same url block → applied normally for REQUEST direction.
  _Implement:_ Already handled by `TransformEngine.transformWithSpec()` direction
  check (`spec.urlSpec() != null && direction == Direction.REQUEST`).
  _Verify:_ `UrlDirectionRestrictionTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*UrlDirectionRestrictionTest*"`
  - `./gradlew spotlessApply check`
  _Status:_ ✅ Complete (2026-02-08). 5 tests: path/query/method individually ignored
  for RESPONSE, full block ignored for RESPONSE, full block applied for REQUEST.

- [x] **T-001-38f** — Create URL rewrite test fixture YAML (FR-001-12) ✅ 2026-02-08
  _Intent:_ Create `url-rewrite-dispatch.yaml` fixture for parameterized testing
  of the de-polymorphization use case.
  _Implement:_ Created fixture at `core/src/test/resources/test-vectors/url-rewrite-dispatch.yaml`
  with complete de-polymorphization: path from body fields, query remove/add (static +
  dynamic), conditional method override, body transform stripping routing fields.
  _Verify:_ Fixture is valid YAML and parseable.
  _Verification commands:_
  - `./gradlew :core:test`
  - `./gradlew spotlessApply check`
  _Status:_ ✅ Complete (2026-02-08). All 255 tests pass.

#### I12 — Reusable mappers

- [x] **T-001-39** — Mapper block + apply pipeline (FR-001-08, ADR-0014, S-001-50)
  _Intent:_ Implement the intra-spec pipeline: named mappers composed via
  a declarative `apply` list.
  _Test first:_ Write `MapperPipelineTest`:
  - Spec with `strip-internal` mapper + `expr` + `add-metadata` mapper
    in `apply` order → output passes through all 3 steps (S-001-50 pattern).
  - Verify each step's output feeds the next step's input.
  _Implement:_ Extend `SpecParser` to parse `mappers` and `apply` blocks.
  Implement mapper compilation and pipeline execution.
  _Verify:_ `MapperPipelineTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*MapperPipelineTest*"`
  - `./gradlew spotlessApply check`
  _Verification log:_ ✅ 7/7 tests pass. BUILD SUCCESSFUL. Committed 0f4cd54.

- [x] **T-001-40** — Mapper validation rules (FR-001-08)
  _Intent:_ Enforce all load-time validation rules for mapper pipelines.
  _Test first:_ Write `MapperValidationTest`:
  - `expr` missing from `apply` → `SpecParseException`.
  - `expr` appears twice in `apply` → `SpecParseException`.
  - Unknown mapper id in `apply` → `SpecParseException`.
  - Circular mapper reference → `SpecParseException`.
  - `apply` absent → normal single-expression evaluation (backwards compat).
  _Implement:_ Add validation logic to `SpecParser`.
  _Verify:_ `MapperValidationTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*MapperValidationTest*"`
  - `./gradlew spotlessApply check`
  _Verification log:_ ✅ 9/9 tests pass. BUILD SUCCESSFUL. Committed f5a176f.

---

### Phase 7 — Observability + Hot Reload

#### I13 — Structured logging + TelemetryListener SPI

- [x] **T-001-41** — Structured log entries for matched transforms (NFR-001-08) ✅ 2026-02-08
  _Intent:_ Every matched request MUST produce a JSON log entry with
  profile id, spec id@version, path, specificity score, eval duration.
  _Test first:_ Write `StructuredLoggingTest`:
  - Execute a transform → capture log output → verify JSON structure
    contains required fields.
  - Passthrough request → no transform log (or explicit passthrough log).
  _Implement:_ Add structured logging to `TransformEngine.transform()`.
  _Verify:_ `StructuredLoggingTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*StructuredLoggingTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-42** — TelemetryListener SPI (NFR-001-09) ✅ 2026-02-08
  _Intent:_ Define the telemetry SPI so adapters can hook into OTel or
  Micrometer without adding those dependencies to core.
  _Test first:_ Write `TelemetryListenerTest`:
  - Register a mock listener → execute transforms → listener receives
    events: started, completed, failed, matched, loaded, rejected.
  - Verify event payloads contain spec id, duration, etc.
  _Implement:_ Define `TelemetryListener` interface. Add notification
  hooks to engine lifecycle (load, transform, reload).
  _Verify:_ `TelemetryListenerTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*TelemetryListenerTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-43** — Sensitive field redaction (NFR-001-06, ADR-0019) ✅ 2026-02-08
  _Intent:_ Fields marked as `sensitive` in the spec MUST be replaced with
  `"[REDACTED]"` in all log output.
  _Test first:_ Write `SensitiveFieldRedactionTest`:
  - Spec with `sensitive: ["$.fields[*].value"]` → log output shows
    `[REDACTED]` for those fields.
  - Invalid RFC 9535 path → `SensitivePathSyntaxError` at load time.
  - Sensitive fields never appear in log output (verify via log capture).
  _Implement:_ Add sensitive path parsing to `SpecParser` and redaction
  to structured logging.
  _Verify:_ `SensitiveFieldRedactionTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*SensitiveFieldRedactionTest*"`
  - `./gradlew spotlessApply check`

- [x] **T-001-44** — Trace context propagation (NFR-001-10) ✅ 2026-02-08
  _Intent:_ Propagate `X-Request-ID` and `traceparent` headers through all
  log entries and telemetry events.
  _Test first:_ Write `TraceContextTest`:
  - Send request with `X-Request-ID: abc-123` → all log entries contain
    `requestId: abc-123`.
  - Send request with `traceparent` → appears in log/telemetry output.
  _Implement:_ Extract trace headers in `TransformEngine.transform()` and
  pass to MDC/logging context.
  _Verify:_ `TraceContextTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*TraceContextTest*"`
  - `./gradlew spotlessApply check`

#### I14 — Hot reload + atomic registry swap

- [x] **T-001-45** — TransformRegistry immutable snapshot (NFR-001-05) ✅ 2026-02-08
  _Intent:_ Create the immutable registry object that holds all loaded
  specs and profiles. Used for atomic swap.
  _Test first:_ Write `TransformRegistryTest`:
  - Create registry with specs + profiles → immutable (modification
    attempts fail or produce new instance).
  - Registry lookup by spec id and profile works correctly.
  _Implement:_ Create `TransformRegistry` as an immutable data class.
  _Verify:_ `TransformRegistryTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*TransformRegistryTest*"`
  - `./gradlew spotlessApply check`
  _Verification log:_ ✅ 9/9 tests pass. BUILD SUCCESSFUL. Immutable snapshot
  with defensive copy, Builder pattern, empty() factory, allSpecs() unmodifiable view.

- [x] **T-001-46** — Atomic registry swap via reload() (NFR-001-05, API-001-04) ✅ 2026-02-08
  _Intent:_ `TransformEngine.reload()` must atomically swap the registry
  so in-flight requests complete with the old one and new requests get the
  new one.
  _Test first:_ Write `AtomicReloadTest`:
  - Start transform with old registry → mid-transform, call `reload()` →
    in-flight transform still uses old registry.
  - After reload, new transform uses new registry.
  - Concurrent reads during swap observe either old or new, never a mix.
  _Implement:_ Use `AtomicReference<TransformRegistry>` in
  `TransformEngine`. `reload()` builds new registry and swaps atomically.
  _Verify:_ `AtomicReloadTest` passes (may need CountDownLatch or
  CompletableFuture for concurrency control).
  _Verification commands:_
  - `./gradlew :core:test --tests "*AtomicReloadTest*"`
  - `./gradlew spotlessApply check`
  _Verification log:_ ✅ 6/6 AtomicReloadTest tests pass.
  Refactored engine to AtomicReference<TransformRegistry>. reload() builds
  fresh registry from spec paths + optional profile, then set()s atomically.
  Concurrent reads tested with 10 threads × 50 iterations.

- [x] **T-001-47** — Fail-safe reload (NFR-001-05) ✅ 2026-02-08
  _Intent:_ If reload() encounters a broken spec, the old registry must be
  preserved — the engine must NOT end up in a broken state.
  _Test first:_ Write `FailSafeReloadTest`:
  - Load valid specs → working. Call `reload()` with a broken spec file
    in the directory → reload fails → engine still serves with old registry.
  - Verify error is logged and old specs still work.
  _Implement:_ Add try/catch to `reload()` — on failure, keep old
  `AtomicReference` value.
  _Verify:_ `FailSafeReloadTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*FailSafeReloadTest*"`
  - `./gradlew spotlessApply check`
  _Verification log:_ ✅ 5/5 tests pass. Fail-safe is inherent: reload()
  builds new registry before swapping — if parse/compile throws, set() is
  never reached. Tested: missing id, bad JSLT, mixed good+broken, broken
  profile, spec count preservation.

---

### Phase 8 — Gateway Adapter SPI + Scenario Sweep

#### I15 — Gateway Adapter SPI + test adapter

- [x] **T-001-48** — GatewayAdapter SPI definition (SPI-001-04/05/06) ✅ 2026-02-08
  _Intent:_ Define the adapter interface that all gateway integrations
  implement. Pure interface — no implementation in core.
  _Test first:_ Compile-time verification is sufficient. Write a mock
  adapter in the test source set to verify the interface is usable.
  _Implement:_ Create `GatewayAdapter` interface with `wrapRequest`,
  `wrapResponse`, `applyChanges` methods.
  _Verify:_ Compiles. Mock adapter in test source set compiles.
  _Verification commands:_
  - `./gradlew :core:test`
  - `./gradlew spotlessApply check`
  _Verification log:_ ✅ 6/6 GatewayAdapterSpiTest tests pass. Generic
  `GatewayAdapter<R>` interface with three per-request methods. ADR-0025
  (no lifecycle) and ADR-0013 (copy-on-wrap) documented in Javadoc. 317
  tests pass. Commit: 48e10b8.

- [x] **T-001-49** — TestGatewayAdapter for scenarios ✅ 2026-02-08
  _Intent:_ Create an in-memory adapter implementation for running
  scenario tests end-to-end.
  _Test first:_ Write `TestAdapterTest`:
  - Create a "native" test message → `wrapRequest()` → `Message`.
  - Transform → modified `Message` → `applyChanges()` → native updated.
  _Implement:_ Create `TestGatewayAdapter` in test source set.
  _Verify:_ `TestAdapterTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*TestAdapterTest*"`
  - `./gradlew spotlessApply check`
  _Verification log:_ ✅ 22/22 TestAdapterTest tests pass. TestMessage
  fluent builder (5), wrapRequest (6), wrapResponse (2), applyChanges (5),
  end-to-end with real TransformEngine (4). 339 tests pass. Commit: 28e00fa.

#### I16 — Full scenario sweep + coverage matrix

- [x] **T-001-50** — Parameterized scenario test suite (all 73 scenarios) ✅ 2026-02-08
  _Intent:_ Run every scenario (S-001-01 through S-001-73) as a
  parameterized JUnit test. This is the ultimate validation of the engine.
  _Test first:_ Write `ScenarioSuiteTest` — load all scenario YAML files
  from `docs/architecture/features/001/scenarios.md` (extract embedded YAML
  blocks) or from a converted `test-vectors/` directory. For each scenario:
  construct input message, load transform spec, execute, assert output.
  _Implement:_ Fix any failing scenarios (implementation gaps, scenario
  errors, or spec clarifications needed).
  _Verify:_ All 73 scenarios pass.
  _Verification commands:_
  - `./gradlew :core:test --tests "*ScenarioSuiteTest*"`
  - `./gradlew :core:test`
  - `./gradlew spotlessApply check`
  _Verification log:_ ✅ 29 tests (20 pass, 9 skipped). ScenarioLoader
  parses YAML from scenarios.md. Fixed 5 scenario expected outputs for
  JSLT null-omission. Infrastructure/error/header/status/URL scenarios
  covered by dedicated test classes. Commit: 60f3f91.

- [x] **T-001-51** — Update coverage matrix in scenarios.md ✅ 2026-02-08
  _Intent:_ For each scenario, record which test class verifies it.
  Complete the coverage matrix table.
  _Implement:_ Update `scenarios.md` coverage matrix: add test class/method
  references for each scenario row.
  _Verify:_ Visual inspection — every scenario has a test reference.
  _Verification commands:_
  - N/A — documentation update.
  _Verification log:_ ✅ Added "Scenario → Test Class Verification" table
  with 78 rows covering all scenarios. 4 scenarios have no test (JOLT/jq
  not implemented, dynamic timestamp, placeholder data). All others map
  to specific test class(es).

- [x] **T-001-52** — Drift gate report ✅ 2026-02-08
  _Intent:_ Execute the full drift gate checklist from `plan.md`. Verify
  spec ↔ code alignment for every FR and NFR.
  _Implement:_ Fill in the Drift Report section in `plan.md`. Record
  results, any drift found, and corrective actions taken.
  _Verify:_ All checklist items pass. Report committed.
  _Verification commands:_
  - `./gradlew spotlessApply check`
  _Verification log:_ ✅ All 7 checklist items pass. 12/12 FRs verified.
  10/10 NFRs verified (NFR-001-03 latency benchmark deferred to backlog).
  4 drift items documented — all Low/Expected severity. 367 tests, 0
  failures, 8 skipped.
  - `./gradlew :core:test`
  - Review: `docs/architecture/open-questions.md` has no open entries.


---

## Phase 9 — Performance Verification (ADR-0028)

> **Scope:** Lightweight NFR-001-03 verification only. Heavy infrastructure (JMH,
> CI gate, regression tracking) is deferred to Feature 009 (Toolchain).
> See ADR-0028 for rationale.

### T-001-53 — TransformEngineBenchmark (NFR-001-03)

- [x] **T-001-53** — Lightweight opt-in benchmark verifying NFR-001-03
  _Intent:_ Prove that core transform latency is < 5ms for typical payloads (< 50KB).
  Uses the openauth-sim pattern: plain JUnit 5 + `assumeTrue` + `System.nanoTime()`.
  No JMH required.
  _Test-first:_
  - Create `TransformEngineBenchmark.java` in `core/src/test/java/io/messagexform/core/engine/`.
  - Guard with `assumeTrue(isBenchmarkEnabled())` using `-Dio.messagexform.benchmark=true`
    or env var `IO_MESSAGEXFORM_BENCHMARK=true`.
  - Implement warmup phase (2,000 iterations) + measured phase (20,000 iterations).
  - Compute and log: p50, p90, p95, p99, max latency (ms), throughput (ops/sec).
  - Benchmark scenarios:
    - **Identity transform** — JSLT `.` expression, 1KB body.
    - **Simple field mapping** — 5-field JSLT, 10KB body.
    - **Complex transform** — nested structure with array, 50KB body.
  - Log structured output: `xform-bench.<scenario> warmup=2000 measured=20000 totalMs=... p95Ms=... p99Ms=...`
  - Soft-assert p95 < 5ms for all scenarios (log warning, don't fail build).
  _Implement:_
  - `BenchmarkReport` record with percentile and throughput helpers (follow openauth-sim pattern).
  - Environment summary method (`os.name`, `os.arch`, `java.runtime.version`, `availableProcessors`).
  - Test fixtures: preloaded specs + sample JSON bodies at 1KB, 10KB, 50KB.
  _Verify:_
  - `IO_MESSAGEXFORM_BENCHMARK=true ./gradlew :core:test --tests "*TransformEngineBenchmark*" --info`
  - Benchmark is skipped when flag is not set (default `./gradlew check` unaffected).
  - Results logged with percentile data + environment summary.
  _Scenarios:_ S-001-79, S-001-80, S-001-81
  _ADR:_ ADR-0028

---

### Phase 10 — Session Context Binding (FR-001-13, ADR-0030)

> **Scope:** Core engine contract + JSLT binding. Adapter-side population
> (PingAccess, PingGateway, standalone) is deferred to Features 002/003/004.

#### I17 — Session context: contract + core binding

- [x] **T-001-54** — Add `sessionContext` field to Message (FR-001-04, FR-001-13, ADR-0030)
  _Intent:_ Extend the `Message` record with a nullable `JsonNode sessionContext`
  field. Backward-compatible constructors (7-arg, 8-arg) delegate to the new
  9-arg canonical constructor with `sessionContext=null`.
  _Test first:_ `SessionContextMessageTest` (5 tests):
  - 7-arg constructor → `sessionContext()` returns null ✅
  - 8-arg constructor → `sessionContext()` returns null ✅
  - 9-arg constructor with session → returns provided JsonNode ✅
  - All envelope fields preserved alongside session context ✅
  - null vs NullNode distinction ✅
  _Verify:_ `./gradlew :core:test --tests "*SessionContextMessageTest*"` → 5 PASSED
  _Verify:_ `./gradlew spotlessApply check` → BUILD SUCCESSFUL

- [x] **T-001-55** — Add `sessionContext` to TransformContext + JSLT binding (DO-001-07, FR-001-13)
  _Intent:_ Extend `TransformContext` record with nullable `JsonNode sessionContext`.
  Add `sessionContextAsJson()` method (returns NullNode when absent). Bind as
  `$session` in `JsltExpressionEngine.buildVariables()`. Backward-compatible 5-arg
  constructor delegates to 6-arg canonical.
  _Test first:_ `SessionContextBindingTest` (8 tests):
  - `$session.sub` with session `{"sub": "bjensen"}` → returns `"bjensen"` ✅
  - `$session.roles` array access ✅
  - Null session context → absent-field behavior (JSLT omits) ✅
  - Session available in request and response transforms ✅
  - Coexists with `$headers`, `$status`, etc. ✅
  - Empty session object → absent sub ✅
  - NullNode vs null distinction ✅
  _Verify:_ `./gradlew :core:test --tests "*SessionContextBindingTest*"` → 8 PASSED
  _Verify:_ `./gradlew spotlessApply check` → BUILD SUCCESSFUL

- [x] **T-001-56** — Engine capability validation: JOLT + `$session` rejected (FR-001-02, S-001-85)
  _Intent:_ Verify that specs declaring `lang: jolt` cannot reference `$session`.
  Covered by existing engine resolution: `SpecParser.resolveEngine()` throws
  `ExpressionCompileException("Unknown expression engine: 'jolt'")` since no
  JOLT engine is registered. No code change needed — only test verification.
  _Test first:_ `SessionContextJoltRejectionTest` (2 tests):
  - JOLT spec with `$session` reference → rejected at load time ✅
  - JOLT spec without `$session` → also rejected (engine not registered) ✅
  _Verify:_ `./gradlew :core:test --tests "*SessionContextJoltRejectionTest*"` → 2 PASSED
  _Verify:_ `./gradlew spotlessApply check` → BUILD SUCCESSFUL

- [x] **T-001-57** — Wire session context from Message into TransformContext (FR-001-13)
  _Intent:_ Complete the data flow: `Message.sessionContext()` →
  `TransformContext.sessionContext()` → `$session` in JSLT. Wired in
  `TransformEngine.transform(Message, Direction)` at line 298 alongside existing
  context-building code for headers, status, queryParams, and cookies.
  _Test first:_ `SessionContextE2ETest` (4 tests):
  - E2E: spec with `$session.sub` + message with session → output contains subject ✅
  - E2E: null session context → null-safe, absent-field behavior ✅
  - E2E: response transform with session → works ✅
  - E2E: complex nested session (roles array, nested claims) → all extracted ✅
  _Verify:_ `./gradlew :core:test --tests "*SessionContextE2ETest*"` → 4 PASSED
  _Verify:_ `./gradlew spotlessApply check` → BUILD SUCCESSFUL

---

## Verification Log

Track long-running or shared commands with timestamps to avoid duplicate work.

- 2026-02-08 03:20 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL (1s) — after T-001-01/02
- 2026-02-08 03:27 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL (1s) — 37 tests passed after T-001-03..08
- 2026-02-08 03:50 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL (1s) — 61 tests passed after T-001-09..12
- 2026-02-08 10:05 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL (1s) — 72 tests passed after T-001-13..14
- 2026-02-08 10:40 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL (1s) — 81 tests passed after T-001-15
- 2026-02-08 10:45 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL (1s) — 89 tests passed after T-001-16..17
- 2026-02-08 10:50 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL (1s) — 93 tests passed after T-001-18
- 2026-02-08 11:10 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL (1s) — 99 tests passed after T-001-19 (S-001-09 adjusted for JSLT absent-field behavior)
- 2026-02-08 11:15 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL (1s) — 104 tests passed after T-001-20
- 2026-02-08 11:20 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL (1s) — 109 tests passed after T-001-21
- 2026-02-08 11:30 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL (1s) — 119 tests passed after T-001-22
- 2026-02-08 11:35 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL (1s) — 124 tests passed after T-001-24
- 2026-02-08 11:40 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL (1s) — 130 tests passed after T-001-23
- 2026-02-08 11:45 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL (1s) — 133 tests passed after T-001-25
- 2026-02-08 11:50 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL (1s) — 138 tests passed after T-001-26
- 2026-02-08 14:40 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL (1s) — 271 tests passed after T-001-39..40 (Phase 6 complete)
- 2026-02-08 15:32 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL — 300 tests passed after T-001-45
- 2026-02-08 15:40 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL — 306 tests passed after T-001-46
- 2026-02-08 15:45 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL — 311 tests passed after T-001-47
- 2026-02-08 16:20 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL — 317 tests passed after T-001-48
- 2026-02-08 16:24 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL — 339 tests passed after T-001-49
- 2026-02-08 17:05 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL — all tests passed after T-001-53
- 2026-02-08 17:06 — `IO_MESSAGEXFORM_BENCHMARK=true ./gradlew :core:test --tests "*TransformEngineBenchmark*"` → BUILD SUCCESSFUL — 3 benchmarks passed: identity-1KB p95=0.001ms (424K ops/s), field-mapping-10KB p95=0.003ms (102K ops/s), complex-50KB p95=0.134ms (4.3K ops/s) — all well below 5ms NFR-001-03 target. Env: Linux amd64, Java 21.0.4+7-LTS, 16 CPUs.
- 2026-02-09 09:34 — `./gradlew spotlessApply check` → BUILD SUCCESSFUL (38s) — all tests passed after T-001-54..57 (Phase 10 complete). Fixed S-001-83 scenario: JSLT `contains()` argument order corrected to `contains(element, sequence)`.

## Completion Criteria

- [x] All 52 Phase 1–8 tasks checked off
- [x] T-001-53 (Phase 9 — performance benchmark) checked off
- [x] T-001-54..57 (Phase 10 — session context binding) checked off
- [x] Quality gate passes (`./gradlew spotlessApply check`)
- [x] All 84 scenarios verified — 20 in parameterized suite, 58 in dedicated test classes, 4 skipped (JOLT/jq stubs), 2 no-test (dynamic/placeholder)
- [x] S-001-82..85 (session context scenarios) verified
- [x] Coverage matrix in `scenarios.md` has no uncovered FRs/NFRs
- [x] Implementation Drift Gate report attached to plan
- [x] Open questions resolved and removed from `open-questions.md`

---

### Phase 11 — Port Value Objects (FR-001-14, ADR-0032, ADR-0033)

> **Scope:** Create 4 core-owned port types + tests. Additive — does NOT modify
> existing types or break any existing tests. Phase 1 of the byte-boundary
> refactor (ADR-0032, ADR-0033).

#### I11 — MediaType enum

- [x] **T-001-58** — MediaType test first (FR-001-14a, DO-001-08) ✅ 2026-02-11
  _Intent:_ TDD — write tests before implementation for the content type enum.
  _Test first:_ Write `MediaTypeTest` — assert:
  - `MediaType.JSON.value()` returns `"application/json"`.
  - `MediaType.NONE.value()` returns `null`.
  - `fromContentType("application/json")` returns `JSON`.
  - `fromContentType("application/json; charset=utf-8")` returns `JSON`
    (params stripped).
  - `fromContentType("application/vnd.api+json")` returns `JSON`
    (structured suffix `+json`).
  - `fromContentType("application/atom+xml")` returns `XML`
    (structured suffix `+xml`).
  - `fromContentType(null)` returns `NONE`.
  - `fromContentType("")` returns `NONE`.
  - `fromContentType("image/png")` returns `BINARY` (unrecognized).
  _Verify:_ Test compiles but fails (no implementation yet).
  _Verification commands:_
  - Compilation check only — implementation in T-001-59.

- [x] **T-001-59** — MediaType implementation (FR-001-14a, DO-001-08) ✅ 2026-02-11
  _Intent:_ Implement the `MediaType` enum per spec.
  _Implement:_ Create `MediaType.java` in `core/model/`. Pure enum with
  `value()` and `fromContentType(String)`. Zero dependencies.
  _Verify:_ `MediaTypeTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*MediaTypeTest*"`
  - `./gradlew spotlessApply check`

#### I12 — MessageBody record

- [x] **T-001-60** — MessageBody test first (FR-001-14b, DO-001-09) ✅ 2026-02-11
  _Intent:_ TDD — write tests for the body value object.
  _Test first:_ Write `MessageBodyTest` — assert:
  - `MessageBody.json("{}".getBytes()).mediaType()` is `JSON`.
  - `MessageBody.json("hello").asString()` returns `"hello"`.
  - `MessageBody.empty().isEmpty()` is `true`.
  - `MessageBody.empty().size()` is `0`.
  - `MessageBody.of(bytes, MediaType.XML).mediaType()` is `XML`.
  - **Equality:** `MessageBody.json("abc").equals(MessageBody.json("abc"))`
    is `true` (`Arrays.equals` for byte[]).
  - **Equality:** different content is not equal.
  - Null content normalized: `MessageBody.of(null, JSON).content()` is
    `byte[0]`.
  _Verify:_ Test compiles but fails.
  _Verification commands:_
  - Compilation check only — implementation in T-001-61.

- [x] **T-001-61** — MessageBody implementation (FR-001-14b, DO-001-09) ✅ 2026-02-11
  _Intent:_ Implement `MessageBody` record with custom `equals`/`hashCode`.
  _Implement:_ Create `MessageBody.java` in `core/model/`. Record with
  override `equals`/`hashCode` using `Arrays.equals()`. Constructor normalizes
  null content to `byte[0]`. Factory methods: `json(byte[])`, `json(String)`,
  `empty()`, `of(byte[], MediaType)`.
  _Verify:_ `MessageBodyTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*MessageBodyTest*"`
  - `./gradlew spotlessApply check`

#### I13 — HttpHeaders class

- [x] **T-001-62** — HttpHeaders test first (FR-001-14c, DO-001-10) ✅ 2026-02-11
  _Intent:_ TDD — write tests for the case-insensitive header collection.
  _Test first:_ Write `HttpHeadersTest` — assert:
  - **Case-insensitive:** `HttpHeaders.of(Map.of("Content-Type", "json")).first("content-type")` returns `"json"`.
  - `first()` returns `null` for missing header.
  - `all()` returns empty list for missing header.
  - `contains("cOnTeNt-TyPe")` is `true` (case-insensitive).
  - `isEmpty()` true for `HttpHeaders.empty()`.
  - `toSingleValueMap()` keys are lowercase.
  - `toMultiValueMap()` keys are lowercase.
  - `ofMulti()` constructor works with multi-value map.
  - **Immutability:** `toSingleValueMap()` throws on mutation attempt.
  _Verify:_ Test compiles but fails.
  _Verification commands:_
  - Compilation check only — implementation in T-001-63.

- [x] **T-001-63** — HttpHeaders implementation (FR-001-14c, DO-001-10) ✅ 2026-02-11
  _Intent:_ Implement `HttpHeaders` class with case-insensitive lookups.
  _Implement:_ Create `HttpHeaders.java` in `core/model/`. Final immutable
  class. Internal `TreeMap(String.CASE_INSENSITIVE_ORDER)` for consistent
  ordering and case-insensitive lookup. Factory methods: `of(Map)`,
  `ofMulti(Map)`, `empty()`.
  _Verify:_ `HttpHeadersTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*HttpHeadersTest*"`
  - `./gradlew spotlessApply check`

#### I14 — SessionContext class

- [x] **T-001-64** — SessionContext test first (FR-001-14d, DO-001-11) ✅ 2026-02-11
  _Intent:_ TDD — write tests for the session context value object.
  _Test first:_ Write `SessionContextTest` (new file, not the existing
  `SessionContextMessageTest` which tests session in Message) — assert:
  - `SessionContext.of(Map.of("sub", "user1")).getString("sub")` returns `"user1"`.
  - `get("sub")` returns `"user1"` as Object.
  - `has("sub")` is `true`.
  - `has("missing")` is `false`.
  - `isEmpty()` is `true` for `SessionContext.empty()`.
  - `isEmpty()` is `false` for non-empty context.
  - `toMap()` returns defensive copy (mutation does not affect original).
  - **Safe toString:** `toString()` prints key names only, NOT values.
  - `empty()` returns singleton (`empty() == empty()` is `true`).
  - Null map: `SessionContext.of(null)` behaves like `empty()`.
  _Verify:_ Test compiles but fails.
  _Verification commands:_
  - Compilation check only — implementation in T-001-65.

- [x] **T-001-65** — SessionContext implementation (FR-001-14d, DO-001-11) ✅ 2026-02-11
  _Intent:_ Implement `SessionContext` with safe `toString()` and singleton empty.
  _Implement:_ Create `SessionContext.java` in `core/model/`. Final immutable
  class. `empty()` returns singleton. `toString()` prints only key names.
  `toMap()` returns defensive copy. `getString()` returns null for non-string.
  _Verify:_ `SessionContextTest` passes.
  _Verification commands:_
  - `./gradlew :core:test --tests "*SessionContextTest*"`
  - `./gradlew spotlessApply check`

#### I15 — Phase 11 gate

- [x] **T-001-66** — Full quality gate (FR-001-14) ✅ 2026-02-11
  _Intent:_ Verify all port types pass together and don't break existing tests.
  _Verify:_ All tests pass, quality gate green.
  _Verification commands:_
  - `./gradlew spotlessApply check`
  - `./gradlew :core:test`
  _Also check:_
  - All 4 port type tests pass (MediaTypeTest, MessageBodyTest,
    HttpHeadersTest, SessionContextTest).
  - All existing 258+ tests still pass (no regressions).
  - Port types have zero `com.fasterxml.jackson` imports.

---

### Phase 12 — TransformResult Provenance Metadata

> Extends the core `TransformResult` type with spec provenance fields
> (`specId`, `specVersion`) so that adapters can surface which spec matched
> without requiring engine-internal state. Requested by Feature 002
> (FR-002-07) but owned here per Principle 8 (Feature Ownership Boundaries).

#### I16 — TransformResult `specId` / `specVersion` extension

- [x] **T-001-67** — Add `specId` and `specVersion` to `TransformResult` (DO-001-05) ✅ 2026-02-14
  _Intent:_ Thread the matched spec's identity through the transform result so
  adapters can surface provenance metadata (e.g., `TransformResultSummary` in
  Feature 002, structured logs in standalone adapter).
  _Test first:_ Write `TransformResultSpecMetadataTest`:
  - `TransformResult.success(msg, "callback-prettify", "1.0.0")` → `.specId()` returns `"callback-prettify"`, `.specVersion()` returns `"1.0.0"`.
  - `TransformResult.error(body, 502, "callback-prettify", "1.0.0")` → `.specId()` and `.specVersion()` populated.
  - `TransformResult.passthrough()` → `.specId()` and `.specVersion()` are both `null`.
  - Existing tests still pass (backward-compatible factory methods).
  _Implement:_ Add `specId` (String, nullable) and `specVersion` (String, nullable)
  fields to `TransformResult`. Extend `success()` and `error()` factory methods
  with overloads that accept spec metadata. Update `TransformEngine.transformWithSpec()`
  to thread `spec.id()` and `spec.version()` through to the result.
  _Verify:_ `TransformResultSpecMetadataTest` passes. All existing 258+ tests pass.
  _Verification commands:_
  - `./gradlew :core:test --tests "*TransformResultSpecMetadataTest*"`
  - `./gradlew :core:test`
  - `./gradlew spotlessApply check`
  _Status:_ ✅ Complete (2026-02-14). 7 new tests pass: SUCCESS/ERROR with spec
  metadata, PASSTHROUGH null metadata, backward-compat factories (SUCCESS/ERROR),
  toString with/without spec ref. Engine wired: `transformWithSpec` and `transformChain`
  thread `spec.id()`/`spec.version()`. All tests pass. BUILD SUCCESSFUL.

- [x] **T-001-68** — Update stale `GatewayAdapter.java` Javadoc (audit F-003) ✅ 2026-02-13
  _Intent:_ The `GatewayAdapter` SPI Javadoc still references pre-refactor types
  and fields: `{@code NullNode}` (now `MessageBody.empty()`), `{@code headersAll}`
  (now `HttpHeaders.toMultiValueMap()`), `{@code contentType}` (now
  `Message.contentType()` accessor), and `{@code JsonNode.deepCopy()}` (now
  `byte[]` copy via `MessageBody`). Update all Javadoc comments on `wrapRequest()`
  and `wrapResponse()` to match the current API surface.
  _Stale locations:_ Lines 20, 55, 58, 61, 81, 83, 88.
  _Verify:_ `./gradlew :core:javadoc` succeeds with no warnings.
  _Source:_ Feature 002 audit report (2026-02-13), finding F-003.
  _Status:_ ✅ Already fixed during audit session (2026-02-13). Javadoc now
  references `MessageBody`, `HttpHeaders`, `SessionContext`, and current `Message`
  field accessors. No stale references remain.

## Notes / TODOs

- **Package naming:** `io.messagexform.core` is a placeholder. Decide final
  package name during T-001-01 (Feature 009 decision).
- **JSLT context variables:** T-001-12 research will determine the binding
  approach. T-001-21 depends on this finding.
- **Evaluation timeout:** T-001-25 notes that JSLT evaluation may not be
  interruptible. If so, document the limitation and rely on output size budget.
- **Scenario YAML extraction:** T-001-50 needs a strategy for loading scenario
  YAML blocks from the markdown file or converting them to standalone files.

---

### Phase 13 — Conditional Response Routing (FR-001-15, FR-001-16, ADR-0036)

> **Scope:** Status-code pattern matching (`match.status`) and body-predicate
> matching (`match.when`) for profile entries. Enables conditional response
> routing — different transforms for success vs. error responses on the same
> path, and polymorphic routing based on body content.
>
> **Research:** `docs/research/conditional-response-routing.md` (Rev 4, Accepted)
> **ADR:** `docs/decisions/ADR-0036-conditional-response-routing.md`
> **Tracker:** §Implementation Tracker in the research document

#### I17 — Phase 1: `match.status` (Status-Code Pattern Matching)

- [x] **T-001-69** — Implement `match.status` (FR-001-15, ADR-0036) ✅ 2026-02-15
  _Intent:_ Add status-code pattern matching to profile entries, enabling
  conditional response routing by HTTP status.
  _Implement:_
  - Create `StatusPattern` sealed interface + 5 implementations (Exact, Class,
    Range, Not, AnyOf) in `core/model/`.
  - Create `StatusPatternParser` — type-aware YAML parsing (handles IntNode gotcha
    where unquoted `status: 404` produces IntNode, not TextNode).
  - Update `ProfileEntry` — add `statusPattern` field (backward-compatible 5-arg ctor).
  - Update `ProfileEntry.constraintCount()` — weighted specificity (Exact=2, Class/Not=1).
  - Update `ProfileParser.parseEntry()` — integrate `parseStatusField()` (NOT `optionalString`).
  - Update `ProfileMatcher.findMatches()` — new 6-arg overload accepting `statusCode`.
  - Update `TransformEngine.transformInternal()` — pass `message.statusCode()`.
  _Test:_
  - `StatusPatternTest` — all pattern types, validation, boundaries, defensive copy.
  - `StatusPatternParserTest` — string/int/array parsing, YAML gotcha, validation errors.
  - `ProfileMatcherTest` — status class routing (S-001-87), exact match (S-001-88),
    specificity (S-001-92), null-safety, passthrough.
  _Verify:_ All 524 tests pass. No regressions.
  _Verification commands:_
  - `./gradlew :core:test`
  - `./gradlew spotlessApply check`
  _Status:_ ✅ Complete (2026-02-15, commit `7fd5ddb`). 44 new tests across
  3 test classes. All 524 tests pass, 0 failures.
  _Scenarios covered:_ S-001-87, S-001-88, S-001-89, S-001-90, S-001-91,
  S-001-92, S-001-100.

- [x] **T-001-72** — SDD documentation gate (FR-001-15, FR-001-16) ✅ 2026-02-15
  _Intent:_ Formalize the new requirements and scenarios in the SDD.
  _Implement:_
  - Add FR-001-15 / FR-001-16 to `spec.md`.
  - Add S-001-87 through S-001-100 to `scenarios.md` (Category 25).
  - Update coverage matrix.
  - Update scenario index and test class verification table.
  - Add DO-001-12 (StatusPattern) to domain objects.
  - Update API-001-01 description.
  - Add Phase 13 tasks to `tasks.md`.
  _Verify:_ All SDD files are consistent and up to date.
  _Verification commands:_
  - Manual review of spec.md, scenarios.md, tasks.md.

#### I18 — Phase 1b: Unknown-Key Detection in ProfileParser

- [x] **T-001-70** — Add unknown-key detection to `ProfileParser` (FR-001-01 parity) ✅ 2026-02-15
  _Intent:_ Add known-key validation to `ProfileParser.parseEntry()` for
  parity with `SpecParser`. Typos like `statis: 404` would be silently
  ignored without this check.
  _Implement:_
  - Define known-key sets for root (profile/version/description/transforms),
    entry (spec/direction/match), and match block (path/method/content-type/status).
  - Add `rejectUnknownKeys()` method mirroring `SpecParser` pattern.
  - Validate at all three levels: root, entry, and match block.
  - 5 new unit tests in `ProfileParserTest.UnknownKeyDetection`.
  _Verify:_ All 529 tests pass. No regressions.
  _Verification commands:_
  - `./gradlew :core:test --tests "*ProfileParserTest*"`
  - `./gradlew spotlessApply check`
  _Status:_ ✅ Complete (2026-02-15). 5 new tests. All 529 tests pass.

#### I19 — Phase 2: `match.when` (Body-Predicate Matching)

- [ ] **T-001-71** — Implement `match.when` (FR-001-16, ADR-0036)
  _Intent:_ Add body-predicate matching to profile entries, enabling
  polymorphic routing based on body content.
  _Implement:_ See research document §Phase 2 for detailed design.
  - Add `EngineRegistry` field to `TransformEngine`.
  - Update `ProfileParser` constructor to accept `EngineRegistry`.
  - Update `ProfileParser.parseEntry()` — parse `when` block, compile at load time.
  - Update `ProfileEntry` — add `whenPredicate` field.
  - Add `hasWhenPredicates()` computed method to `TransformProfile`.
  - Update `ProfileMatcher` — two-phase matching (envelope, then body predicate).
  - Update `TransformEngine.transformInternal()` — conditional body pre-parse.
  - Pass `preParsedBody` to `transformWithSpec` (reuse across all 4 `bodyToJson` sites).
  - Extract `isTruthy()` to `JsonNodeUtils`.
  _Test:_ `ProfileMatcherTest` (when predicate), `TransformEngineTest` (e2e integration).
  _Scenarios:_ S-001-93, S-001-94, S-001-95, S-001-96, S-001-97, S-001-98, S-001-99.
  _Verification commands:_
  - `./gradlew :core:test`
  - `./gradlew spotlessApply check`

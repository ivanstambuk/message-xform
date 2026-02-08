# Pending Task

**Focus**: Phase 6 — Headers, Status, URL, Mappers (I10–I11)
**Status**: Not started — Phase 5 fully complete and committed
**Next Step**: Begin T-001-34 — Header add/remove/rename operations

## Context Notes
- 181 tests passing, build clean (`./gradlew spotlessApply check`)
- TransformEngine now supports profiles, chaining, bidirectional, logging
- Per-task commit discipline codified: one task = one commit (AGENTS.md §5)
- Logback-classic is `testImplementation` (not `testRuntimeOnly`) for ListAppender access

## Phase 6 Task Sequence
1. **T-001-34** — Header add/remove/rename operations (HeaderTransformer)
2. **T-001-35** — Dynamic header expressions using JSLT
3. **T-001-36** — Header name normalization to lowercase (RFC 9110)
4. **T-001-37** — Status code override with `when` predicate
5. **T-001-38** — `$status` binding in JSLT
6. **T-001-38a** — URL path rewrite with JSLT expression
7. **T-001-38b** — URL query parameter add/remove

## SDD Gaps
- None identified. Retro completed with all fixes applied.
- S-001-74 (chain step logging scenario) was added during retro.

## Key Files
- Tasks: `docs/architecture/features/001/tasks.md` (lines ~535+ for Phase 6)
- Spec: `docs/architecture/features/001/spec.md` (FR-001-10, FR-001-11, FR-001-12)
- Engine: `core/src/main/java/io/messagexform/core/engine/TransformEngine.java`
- Scenarios: `docs/architecture/features/001/scenarios.md` (S-001-33..38g for headers/status/URL)

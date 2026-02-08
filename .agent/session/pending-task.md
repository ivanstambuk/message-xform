# Pending Task

**Focus**: Phase 5 — Profiles + Matching (I8)
**Status**: Ready to begin — Phase 4 complete, 139 tests passing, quality gate clean
**Next Step**: Start T-001-27 — Create profile test fixture YAML

## Context Notes
- Phase 4 (core transform pipeline) is fully complete: T-001-19..26
- TransformEngine now has: loadSpec, transform, passthrough, context binding,
  error response (RFC 9457), custom error templates, eval budgets, strict-mode
- All 8 tasks committed individually with passing quality gates
- No WIP or temporary state — clean working tree

## Key Classes Added This Session
- `ErrorResponseBuilder` — RFC 9457 + custom template modes
- `EvalBudget` — record for max-eval-ms + max-output-bytes
- `SchemaValidationMode` — STRICT/LENIENT enum
- `TransformEngine` now has 4 constructor overloads (1-arg through 4-arg)

## Phase 5 Tasks (T-001-27..33)
Reference: `docs/architecture/features/001/tasks.md` lines 440+
- T-001-27: Profile test fixture YAML
- T-001-28: ProfileParser
- T-001-29: Path matching with specificity scoring (ADR-0006)
- T-001-30: Version resolution
- T-001-31: Chaining
- T-001-32: Direction consistency
- T-001-33: ProfileRegistry integration with TransformEngine

## SDD Gaps
- None identified — retro audit passed all checks
- JSLT eval budget limitation documented in `docs/research/expression-engine-evaluation.md`

## Build Status
- 139 tests passing
- `./gradlew spotlessApply check` → BUILD SUCCESSFUL
- Java 21, Gradle 9.2.0, JSLT 0.1.14

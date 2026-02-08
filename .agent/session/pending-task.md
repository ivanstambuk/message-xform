# Pending Task

**Focus**: Feature 001 — Phase 3: Spec Parsing + Loading (T-001-13..20)
**Status**: Phase 1–2 fully complete and committed. Phase 3 not started.
**Next Step**: Begin T-001-13 — Create YAML test fixture files in `core/src/test/resources/test-vectors/`.

## Context Notes
- Build is green: 61 tests passing, `./gradlew spotlessApply check` → BUILD SUCCESSFUL.
- Tasks file: `docs/architecture/features/001/tasks.md` — T-001-01..12 checked off.
- The task list has detailed instructions for each task including verification commands.
- JSLT engine is fully functional with context variable injection.
- EngineRegistry supports thread-safe registration and lookup.

## Phase 3 Task Summary (T-001-13..20)
- T-001-13: Create YAML test fixture files (3 golden vectors)
- T-001-14: TransformSpec record (immutable Java record)
- T-001-15: SpecParser (YAML → TransformSpec)
- T-001-16: Spec validation (id, version, lang)
- T-001-17: Transform profile record
- T-001-18: Profile-spec resolution
- T-001-19: SpecLoader (file → parsed + validated spec)
- T-001-20: SpecLoader error handling

## SDD Gaps (if any)
- None identified. All retro checks passed ✅.

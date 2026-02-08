# Pending Task

**Focus**: Phase 3 — Spec Parsing + Loading
**Status**: Ready to start (Phases 1–2 complete, all design work done)
**Next Step**: T-001-13 — Create test fixture YAML files

## Context Notes
- This session was entirely spec/design work: FR-001-12 (URL Rewriting),
  ADR-0027, Rule 18, 7 scenarios, 6 tasks, terminology updates.
- No implementation changes — build is green at 61 tests.
- Phase 3 starts with creating YAML test fixtures (T-001-13), then parsing
  them into TransformSpec (T-001-14). TDD cadence: tests first.
- The `url` block in TransformSpec needs to be parsed by SpecParser (Phase 6,
  T-001-38a), but the domain model + spec are ready for it when we get there.

## Key Files for Phase 3
- `docs/architecture/features/001/tasks.md` — T-001-13 through T-001-18
- `docs/architecture/features/001/spec.md` — FR-001-01 (spec format), DO-001-02
- `core/src/test/resources/test-vectors/` — where fixtures go
- `core/src/main/java/.../core/spec/` — where SpecParser will live

## SDD Gaps
- None identified. Retro audit passed all checks.
- ADR-0027 validating scenarios added during retro.

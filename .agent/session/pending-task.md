# Pending Task

**Focus**: Phase 6 I11 — Status Code Transformations (T-001-37, T-001-38)
**Status**: Not started — I10 (Header transformations) fully complete
**Next Step**: Begin T-001-37 (status code override with `set` + `when` predicate)

## Context Notes
- I10 completed cleanly: HeaderSpec, HeaderTransformer, SpecParser extensions, 18 new tests (199 total)
- TransformSpec now has a `headerSpec` field (nullable, backward-compatible via convenience constructor)
- The same pattern (model record + transformer class + SpecParser extension + TransformEngine integration) should apply to status code transforms
- Dynamic expr compilation pattern is established — reuse for `status.when` predicate
- S-001-34 scenario was fixed during retro (dynamic expr must reference transformed body, not input body)

## Key Files for Next Session
- `TransformEngine.transformWithSpec()` — integration point for status transforms
- `SpecParser.parse()` — will need `parseStatusSpec()` similar to `parseHeaderSpec()`
- `docs/architecture/features/001/tasks.md` lines 578-600 — T-001-37/38 task definitions
- `docs/architecture/features/001/spec.md` lines 846-880 — FR-001-11 spec
- ADR-0003 (Status Code Transforms) and ADR-0017 ($status null for requests)

## SDD Gaps (if any)
- None identified. Retro was clean except for the S-001-34 fix (already applied).

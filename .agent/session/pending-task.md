# Pending Task

**Focus**: Implement Conditional Response Routing (ADR-0036)
**Status**: Phase 1 + SDD complete; Phase 1b / Phase 2 remain
**Next Step**: Phase 1b (unknown-key detection) or Phase 2 (match.when)

## Context Notes
- Research proposal: `docs/research/conditional-response-routing.md` (Rev 4, Accepted)
- **Implementation tracker**: same document, §Implementation Tracker (checkbox progress)
- ADR: `docs/decisions/ADR-0036-conditional-response-routing.md`
- Implementation is 2 remaining chunks: Phase 1b, Phase 2
- All design decisions are finalized — no open questions remain

## ✅ Complete

### Phase 1: `match.status` (2026-02-15, commit `7fd5ddb`)
- `StatusPattern` sealed interface (Exact, Class, Range, Not, AnyOf)
- `StatusPatternParser` — type-aware YAML parsing
- `ProfileEntry`, `ProfileParser`, `ProfileMatcher`, `TransformEngine` updated
- 44 new tests across 3 test classes, all 524 tests pass

### SDD Documentation (2026-02-15)
- FR-001-15 / FR-001-16 added to `spec.md`
- S-001-87 – S-001-100 added to `scenarios.md` (Category 25)
- DO-001-12 (StatusPattern) added to domain objects
- Coverage matrix, scenario index, test verification table updated
- Phase 13 tasks (T-001-69–72) added to `tasks.md`

## Remaining Work

### Phase 1b: Unknown-key detection in `ProfileParser` (T-001-70)
1. Add known-key set to `parseEntry()` (parity with `SpecParser`)
2. Warn or reject unknown keys in `match` block
3. Unit tests for unknown-key detection

### Phase 2: `match.when` — Body-Predicate Matching (T-001-71)
1. Add `EngineRegistry` field to `TransformEngine` (constructor change)
2. Update `ProfileParser` constructor to accept `EngineRegistry`
3. Update `ProfileParser.parseEntry()` — parse `when` block, compile expression
4. Update `ProfileEntry` — add `whenPredicate` field
5. Add `hasWhenPredicates()` method to `TransformProfile` (computed, NOT record component)
6. Update `ProfileMatcher` — two-phase matching (envelope, then body predicate)
7. Update `TransformEngine.transformInternal()` — conditional body pre-parse
8. Pass `preParsedBody` to `transformWithSpec` (reuse across all 4 bodyToJson sites)
9. Extract `isTruthy()` to `JsonNodeUtils`

## Critical v4 Findings to Remember
- YAML `status: 404` (unquoted) → IntNode, NOT TextNode. Use `parseStatusField()`
- `StatusPattern.matches(int)` takes primitive; guard null Integer in ProfileMatcher
- `TransformProfile` can't have `hasWhenPredicates` as record component — use method
- Pre-parsed body reuse must cover ALL 4 bodyToJson() call sites in transformWithSpec

# Pending Task

**Focus**: Implement Conditional Response Routing (ADR-0036)
**Status**: Design complete, implementation not started
**Next Step**: Formalize FRs and scenarios in spec.md/scenarios.md, then implement Phase 1

## Context Notes
- Research proposal: `docs/research/conditional-response-routing.md` (Rev 4, Accepted)
- ADR: `docs/decisions/ADR-0036-conditional-response-routing.md`
- Implementation is 2 phases: Phase 1 (match.status) then Phase 2 (match.when)
- All design decisions are finalized — no open questions remain

## Implementation Order (Phase 1: match.status)
1. Add FR-001-15/16 to `docs/architecture/features/001/spec.md`
2. Add S-001-87–S-001-100 to `docs/architecture/features/001/scenarios.md`
3. Create `StatusPattern` sealed interface + implementations (Exact, Class, Range, Not, AnyOf)
4. Create `StatusPatternParser` (handles string, integer, array YAML nodes)
5. Update `ProfileEntry` record — add `statusPattern` field
6. Update `ProfileEntry.constraintCount()` — weighted specificity
7. Update `ProfileParser.parseEntry()` — use `parseStatusField()` (NOT optionalString)
8. Update `ProfileMatcher.findMatches()` — accept statusCode param
9. Update `TransformEngine.transformInternal()` — pass `message.statusCode()`
10. Add unknown-key detection to `ProfileParser` (Phase 1b)

## Implementation Order (Phase 2: match.when)
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

## SDD Gaps (deferred to implementation)
- FR-001-15/16 not yet in spec.md
- S-001-87–S-001-100 not yet in scenarios.md
- Coverage matrix not yet updated

# Current Session

**Focus**: Conditional Response Routing — Research, Design, and ADR
**Date**: 2026-02-15
**Status**: Complete — ready for implementation

## Accomplished

1. Authored comprehensive research proposal for conditional response routing
   (`docs/research/conditional-response-routing.md`, Revision 4, Accepted).
   - 4 deep-pass revisions identifying 26+ gaps across 4 angles:
     type system, parser correctness, API contracts, and cross-feature interactions.
   - Defined `match.status` (Phase 1) and `match.when` (Phase 2).

2. Created ADR-0036 — Conditional Response Routing:
   - Full options analysis with comparison matrix.
   - Phase 1: status pattern matching (exact, class, range, negation, list).
   - Phase 2: body predicate matching via compiled JSLT expressions.
   - Specificity weighting, evaluation order, pipeline interaction.
   - `status.when` vs `match.status` disambiguation.

3. Updated 3 existing ADRs with cross-references:
   - ADR-0003: disambiguation section for status.when vs match.status.
   - ADR-0006: weighted specificity, when-predicate ambiguity exemption.
   - ADR-0012: body predicate timing (original body, not pipeline intermediate).

4. Added FR-001-15 (Status Routing) and FR-001-16 (Body Predicate Routing)
   to knowledge map and llms.txt.

## Key Decisions

- Proposal accepted after 4 revision passes — design is stable.
- `match.status` is response-direction only; `match.when` is both directions.
- Weighted specificity: exact/range = +2, class/negation = +1, when = +1.
- `when` entries exempt from load-time ambiguity rejection.
- Pre-parsed body reuse across all 4 `bodyToJson()` call sites.
- `TransformProfile.hasWhenPredicates()` is a computed method, not record component.
- `parseStatusField()` handles YAML IntNode, TextNode, ArrayNode (not `optionalString()`).

## Next Session Focus

- Begin implementation of conditional response routing (Phase 1 first).
- First task: add FR-001-15/16 to spec.md and S-001-87–S-001-100 to scenarios.md.
- Then implement StatusPattern type hierarchy, ProfileParser changes, ProfileMatcher updates.
- ProfileParser needs EngineRegistry dependency (Phase 2 blocker — plan constructor change).

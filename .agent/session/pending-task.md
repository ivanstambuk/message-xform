# Pending Task

**Focus**: Resolving remaining open questions for Feature 001 (Q-022 through Q-027)
**Status**: Q-022 decision card was presented in the previous session — user has not yet decided
**Next Step**: Re-present Q-022 decision card (Content-Type negotiation) and get user's choice, then resolve

## Context Notes
- Q-022 recommendation is Option A (adapter responsibility for form→JSON coercion)
- Two HIGH-severity questions remain: Q-025 (lenient mode) and Q-027 (cross-profile conflicts) — prioritize these
- The retro found and fixed all SDD gaps — scenarios, terminology, ADR refs are clean
- AGENTS.md now has Rule 5 (Incremental Auto-Commit Protocol) — commit after each logical increment

## SDD Gaps (if any)
- None — retro audit cleared all gaps
- ADR-0020 and ADR-0021 now have validating scenarios (S-001-63/64/65)
- Terminology updated for $queryParams, $cookies, nullable Integer

## After Open Questions
- Run the Cross-Language Portability Audit (backlog item in PLAN.md)
- Then proceed to Feature 001 implementation planning

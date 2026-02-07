# Current Session — 2026-02-07 (Session 4)

## Focus
Deep spec review → resolve terminology and open questions for Feature 001.

## Key Decisions
- **Terminology**: All "runtime" → "evaluation time" (11 instances in spec.md).
  Aligned with JourneyForge convention. Codified in terminology.md.
- **ADR-0020**: `TransformContext.getStatusCode()` changed from `int` (-1 sentinel)
  to `Integer` (nullable). Cross-language portable. Resolves Q-020.
- **ADR-0021**: Added `$queryParams` and `$cookies` to `TransformContext`.
  Query params as JsonNode (keys=names, values=first value). Cookies are
  request-side only (from Cookie header). Resolves Q-021.
- **Backlog**: Cross-language portability audit added to PLAN.md — revisit all
  interfaces and ADRs for language-agnostic portability after open questions resolved.

## Commits This Session
- `9c45abc` terminology: replace all 'runtime' with 'evaluation time'
- `758ca03` Q-020 resolved: nullable Integer for status code (ADR-0020)
- `69ccd00` Q-021 resolved: add $queryParams and $cookies (ADR-0021)
- `c62bc63` backlog: add cross-language portability audit
- `ae57d6c` retro: SDD completeness audit — scenarios + terminology fixes

## Open Questions Remaining (6)
- Q-022: Content-Type negotiation beyond JSON (MEDIUM) — decision card presented
- Q-023: Multi-value header access (LOW-MEDIUM)
- Q-024: Error type hierarchy underspecified (MEDIUM)
- Q-025: Lenient mode underspecified (HIGH)
- Q-026: GatewayAdapter SPI lifecycle gaps (MEDIUM)
- Q-027: Cross-profile match conflicts (HIGH)

## Next Steps
1. Resolve Q-022 (content-type — Option A recommended, awaiting confirmation)
2. Resolve Q-025 (lenient mode — HIGH priority)
3. Resolve Q-027 (cross-profile conflicts — HIGH priority)
4. Resolve remaining medium/low questions (Q-023, Q-024, Q-026)
5. Run cross-language portability audit (backlog)

# Pending Task

**Focus**: Feature 001 open question resolution
**Status**: 2 of 5 high/medium questions resolved this session (Q-025, Q-027). 3 remain.
**Next Step**: Present Q-024 (Error type hierarchy) as a Decision Card.

## Context Notes
- Q-024 asks whether the spec should enumerate a full error type catalogue (SpecLoadException, ProfileLoadException, SchemaValidationException, etc.) for adapter implementors. PingAccess specifically needs error-to-Outcome mapping.
- Q-026 asks about adapter SPI lifecycle (init, shutdown, reload). This may be scoped down by ADR-0023's insight that much adapter behaviour is product-defined.
- Q-023 (multi-value headers) is LOW-MEDIUM and can be deferred.
- Decision Card format was updated this session — use the new progressive disclosure template (Introduction → Define before use → Technical detail).
- AGENTS.md Rule 11 (No Implicit Decisions) is new — every decision must be captured in an ADR, spec, terminology, or scenarios. Never leave decisions as implicit chat understanding.

## SDD Gaps (if any)
- None identified in retro audit. All checks passed.

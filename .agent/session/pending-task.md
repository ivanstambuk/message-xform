# Pending Task

**Focus**: Feature 001 audit complete; Feature 002 implementation next
**Status**: All F001 audit findings resolved. Ready to resume F002 implementation.
**Next Step**: Implement T-001-67 (specId/specVersion on TransformResult)

## Context Notes
- T-001-67 is a Feature 002 dependency — TransformResult needs provenance metadata
  so adapters can surface which spec matched (FR-002-07)
- T-001-68 (GatewayAdapter Javadoc) is independent and can be done in any order
- After T-001-67/68, resume Feature 002: T-002-01 (ErrorMode/SchemaValidation enums)

## SDD Gaps (if any)
- None — retro findings ledger was resolved in-session

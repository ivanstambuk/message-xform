# Pending Task

**Focus**: Phase 3 — Spec Parsing + Loading (Feature 001)
**Status**: T-001-13 and T-001-14 complete. Next is T-001-15 (error handling).
**Next Step**: Implement `SpecParserTest` negative cases and `SpecParser` error paths.

## Context Notes
- `TransformSpec` record created in `core/src/main/java/io/messagexform/core/model/TransformSpec.java`
- `SpecParser` created in `core/src/main/java/io/messagexform/core/spec/SpecParser.java`
- 3 fixture YAML files in `core/src/test/resources/test-vectors/` (FX-001-01/02/03)
- 72 tests passing, build clean
- IDE upgraded: `redhat.java` v1.12 → v1.53 for Java 21 support
- `.vscode/settings.json` created (gitignored) with JDK 21 runtime config

## Remaining Phase 3 Tasks
- **T-001-15** — Error handling: missing fields, bad YAML, unknown engine → SpecParseException
- **T-001-16** — Create bidirectional fixture (FX-001-04 with forward/reverse blocks)
- **T-001-17** — Parse bidirectional specs (forward + reverse expressions)
- **T-001-18** — Schema validation on parsed specs (FR-001-09)

## SDD Gaps
- None identified — retro audit was clean across all 5 checks.

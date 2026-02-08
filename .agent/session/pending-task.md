# Pending Task

**Focus**: Feature 001 — implementation planning  
**Status**: Spec is complete (73 scenarios, 0 open questions, 26 ADRs). Ready to begin planning.  
**Next Step**: Draft `docs/architecture/features/001/plan.md` — break the spec into implementation phases, milestones, and task breakdown.

## Context Notes
- All open questions (Q-023, Q-024, Q-026, Q-028) resolved this session.
- Deep spec review completed — 14 findings fixed, 2 gap scenarios added during retro audit.
- AGENTS.md updated with STT note (user uses speech-to-text).
- The spec's Interface & Contract Catalogue and Spec DSL are now fully aligned.
- `TransformResult` fields are now defined (SUCCESS/ERROR/PASSTHROUGH + diagnostics).
- `TransformProfile` is now in the DSL (DO-001-08).
- `Message` interface now includes `addHeader()`, `getRequestPath()`, `getRequestMethod()`.

## SDD Gaps
- None identified. Retro audit passed clean after fixes.

## Suggested Plan Structure
When drafting `plan.md`, consider these implementation phases:
1. **Phase 1**: Core domain objects (`Message`, `TransformSpec`, `TransformContext`, `TransformResult`)
2. **Phase 2**: JSLT expression engine (compile + evaluate) + engine SPI
3. **Phase 3**: Spec loading (YAML parse → compile → validate schemas)
4. **Phase 4**: Profile loading + match resolution
5. **Phase 5**: Full pipeline (`transform()` — match → evaluate → headers → status)
6. **Phase 6**: Error handling (exception hierarchy, error responses, eval budgets)
7. **Phase 7**: Hot reload + telemetry SPI

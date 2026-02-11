# Pending Task

**Focus**: Documentation cleanup — ADR hygiene, spike absorption, ADR creation
**Status**: Complete for this session. All spike documents deleted and findings absorbed.
**Next Step**: Begin implementing the PA dependency build integration (Feature 002):
  - Create `gradle/pa-provided.versions.toml`
  - Register it as `paProvided` catalog in `settings.gradle.kts`
  - Update `adapter-pingaccess/build.gradle.kts` with `compileOnly` deps
  - Configure shadow JAR excludes
  - Implement runtime version guard

## Context Notes
- ADR-0031 is now the single source of truth for PA dependency strategy,
  classloader model, library inventory, build integration options, release
  strategy, and version guard design.
- ADR-0032 supersedes the Jackson coupling aspect of ADR-0031 — core
  manages its own Jackson independently (relocated behind byte[] boundary).
- ADR-0034 documents the SLF4J decision (compile against 1.7.36, no port).
- AGENTS.md now has a strengthened ADR purity rule: stale content must
  be removed, not annotated.
- The extraction script `scripts/pa-extract-deps.sh` exists and is
  referenced in ADR-0031 but has not been integrated into the build yet.

## SDD Gaps (if any)
- None identified. All checks passed.

# Pending Task

**Focus**: Phase 6 I12 — Reusable mappers (T-001-39, T-001-40)
**Status**: Not yet started. I11a (URL rewriting) fully complete.
**Next Step**: Write `MapperPipelineTest` (T-001-39, test-first)

## Context Notes
- All URL rewriting tasks (T-001-38a..38f) are complete and committed
- 255 tests passing, all quality gates green
- I12 introduces `mappers` block and `apply` pipeline (FR-001-08, ADR-0014)
- S-001-50 scenario defines the expected pipeline behavior
- Message record now has 8 fields including `queryString` (backward-compat constructor)

## SDD Gaps (if any)
- None identified. Full SDD audit passed clean.

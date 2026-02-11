# Feature 002 — Spec Gap Review Tracker

Created: 2026-02-11 | Session: spec self-critique and revision

## Batch 1 — Critical (Gaps 1–4) ✅

- [x] Gap 1: TransformContext is completely missing — added FR-002-13
- [x] Gap 2: applyChanges direction ambiguity — added Direction Strategy section
- [x] Gap 3: DENY error-mode in handleResponse — clarified as response rewrite, not pipeline halt
- [x] Gap 4: Body.read() error handling — specified NullNode fallback on AccessException/IOException

## Batch 2 — Medium (Gaps 5–8)

- [ ] Gap 5: No wrapRaw fallback pattern — decide on approach and document
- [ ] Gap 6: Header removal detection in applyChanges — specify diff strategy
- [ ] Gap 7: Wrong JSLT variable names in S-002-22 / S-002-23 — fix to $cookies / $queryParams
- [ ] Gap 8: reloadIntervalSec config — clarify scope (spec YAML only, not JAR)

## Batch 3 — Medium (9–11) + Low (12–16)

- [ ] Gap 9: tokenExpiration missing from session context — add to FR-002-06
- [ ] Gap 10: SLF4J classpath conflict + TelemetryListener strategy — add to FR-002-09 / Constraints
- [ ] Gap 11: 2-arg vs 3-arg transform — mandate 3-arg in FR-002-02
- [ ] Gap 12: getOriginalRequestUri vs getRequest().getUri() — clarify in wrapRequest mapping
- [ ] Gap 13: Query param encoding + URISyntaxException handling — clarify in FR-002-13
- [ ] Gap 14: Jackson shadow/relocation strategy — add to FR-002-09
- [ ] Gap 15: ErrorHandlingCallback — document default strategy
- [ ] Gap 16: Method.forName() verification — verify SDK and fix mapping table

# Feature 002 — Spec Gap Review Tracker

Created: 2026-02-11 | Session: spec self-critique and revision

## Batch 1 — Critical (Gaps 1–4) ✅

- [x] Gap 1: TransformContext is completely missing — added FR-002-13
- [x] Gap 2: applyChanges direction ambiguity — added Direction Strategy section
- [x] Gap 3: DENY error-mode in handleResponse — clarified as response rewrite, not pipeline halt
- [x] Gap 4: Body.read() error handling — specified NullNode fallback on AccessException/IOException

## Batch 2 — Medium (Gaps 5–8) ✅

- [x] Gap 5: No wrapRaw fallback pattern — decided: swallow internally, return NullNode
- [x] Gap 6: Header removal detection in applyChanges — specified diff-based strategy
- [x] Gap 7: Wrong JSLT variable names in S-002-22 / S-002-23 — fixed to $cookies / $queryParams
- [x] Gap 8: reloadIntervalSec config — clarified scope (spec YAML only) + ScheduledExecutorService

## Batch 3 — Medium (9–11) + Low (12–16) ✅

- [x] Gap 9: tokenExpiration missing from session context — added to FR-002-06 with ISO 8601 formatting
- [x] Gap 10: SLF4J classpath conflict + TelemetryListener strategy — SLF4J excluded (compileOnly), Jackson relocate SHOULD, no custom TelemetryListener
- [x] Gap 11: 2-arg vs 3-arg transform — already fixed in Batch 1 (FR-002-02 mandates 3-arg)
- [x] Gap 12: getOriginalRequestUri vs getRequest().getUri() — added URI choice note, decided on getUri() (current URI for profile matching)
- [x] Gap 13: Query param encoding + URISyntaxException handling — already covered in FR-002-13 (Batch 1)
- [x] Gap 14: Jackson shadow/relocation strategy — added Jackson relocation note to FR-002-09
- [x] Gap 15: ErrorHandlingCallback — already covered in Batch 1 (FR-002-02 point 3, RuleInterceptorErrorHandlingCallback)
- [x] Gap 16: Method.forName() verification — verified via javap, method exists ✅ (no fix needed)

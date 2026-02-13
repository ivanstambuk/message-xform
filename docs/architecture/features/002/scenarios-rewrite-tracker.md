# Feature 002 Scenarios Rewrite — Tracker

**Objective:** Rewrite all 36 Feature 002 scenarios from BDD prose to structured
YAML behavioral contracts per `docs/architecture/spec-guidelines/scenarios-format.md`.

**Status:** In Progress

---

## Phases

| Phase | Scenarios | Count | Status |
|-------|-----------|-------|--------|
| 0 | Preamble + format guideline | — | ✅ Done |
| 1 | Core transforms: S-002-01 → S-002-06 | 6 | ✅ Done |
| 2 | Edge cases + matching: S-002-07 → S-002-10 | 4 | ✅ Done |
| 3 | Error modes + session: S-002-11 → S-002-14 | 4 | ✅ Done |
| 4 | Config + SPI + threading: S-002-15 → S-002-21 | 7 | ✅ Done |
| 5 | Context variables: S-002-22 → S-002-27 | 6 | ⬜ |
| 6 | Advanced behavioral: S-002-28 → S-002-36 | 9 | ⬜ |

**Total:** 36 scenarios across 6 phases.

---

## Phase Details

### Phase 1 — Core Transforms (S-002-01 → S-002-06)

The bread-and-butter adapter scenarios: request/response body, bidirectional,
header, status code, and URL rewrite.

- [x] S-002-01: Request Body Transform
- [x] S-002-02: Response Body Transform
- [x] S-002-03: Bidirectional Transform
- [x] S-002-04: Header Transform
- [x] S-002-05: Status Code Transform
- [x] S-002-06: URL Rewrite

### Phase 2 — Edge Cases + Matching (S-002-07 → S-002-10)

Empty/non-JSON body handling and profile matching behavior.

- [x] S-002-07: Empty Body
- [x] S-002-08: Non-JSON Body
- [x] S-002-09: Profile Matching
- [x] S-002-10: No Matching Spec

### Phase 3 — Error Modes + Session (S-002-11 → S-002-14)

Error handling modes and session context binding.

- [x] S-002-11: Error Mode PASS_THROUGH
- [x] S-002-12: Error Mode DENY
- [x] S-002-13: Session Context in JSLT
- [x] S-002-14: No Identity (Unauthenticated)

### Phase 4 — Config + SPI + Threading (S-002-15 → S-002-21)

Plugin lifecycle, configuration, SPI registration, threading, and metadata.

- [x] S-002-15: Multiple Specs Loaded
- [x] S-002-16: Large Body (64 KB)
- [x] S-002-17: Plugin Configuration via Admin UI
- [x] S-002-18: Invalid Spec Directory
- [x] S-002-19: Plugin SPI Registration
- [x] S-002-20: Thread Safety
- [x] S-002-21: ExchangeProperty Metadata

### Phase 5 — Context Variables (S-002-22 → S-002-27)

JSLT context variable binding: cookies, query params, OAuth, session state,
prior rule effects.

- [ ] S-002-22: Cookie Access in JSLT
- [ ] S-002-23: Query Param Access in JSLT
- [ ] S-002-24: Shadow JAR Correctness
- [ ] S-002-25: OAuth Context in JSLT
- [ ] S-002-26: Session State in JSLT
- [ ] S-002-27: Prior Rule URI Rewrite

### Phase 6 — Advanced Behavioral (S-002-28 → S-002-36)

Complex lifecycle interactions, hot reload, JMX, and version guards.

- [ ] S-002-28: DENY + handleResponse Interaction
- [ ] S-002-29: Spec Hot-Reload (Success)
- [ ] S-002-30: Spec Hot-Reload (Failure)
- [ ] S-002-31: Concurrent Reload During Active Transform
- [ ] S-002-32: Non-JSON Response Body
- [ ] S-002-33: JMX Metrics Opt-In
- [ ] S-002-34: JMX Metrics Disabled (Default)
- [ ] S-002-35: PA-Specific Non-Standard Status Codes Passthrough
- [ ] S-002-36: Runtime Version Mismatch Warning

---

## Completion Criteria

- [ ] All 36 scenarios converted to YAML behavioral contract format
- [ ] Preamble metadata normalized with Feature 001
- [ ] Coverage matrix appended (audit F-005)
- [ ] Committed and pushed
- [ ] Tracker file deleted (ephemeral artifact)

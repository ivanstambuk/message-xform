# Feature 002 — E2E Validation Record

| Field | Value |
|-------|-------|
| Script | `scripts/pa-e2e-test.sh` |
| Spec files | `e2e/pingaccess/specs/e2e-rename.yaml`, `e2e-header-inject.yaml` |
| Linked requirement | FR-002-12 |

> This is a living document. Update it each time the E2E suite is run against
> a live PingAccess instance. Append new entries to the Run History table.

---

## Latest Run

| Field | Value |
|-------|-------|
| Date | 2026-02-14T14:58 CET |
| Result | **18/18 PASSED** ✅ |
| Script commit | `093c688` |
| PA version | 9.0.1.0 |
| PA Docker image | `pingidentity/pingaccess:latest` |
| Shadow JAR size | 4.6 MB (< 5 MB NFR-002-02) |
| Class file version | 61 (Java 17) |
| Build time | 4s |
| PA startup time | 24s |

### Test Breakdown

| # | Test | Assertions | Result |
|---|------|------------|--------|
| 1 | Bidirectional body round-trip (snake↔camel) | POST 200, `user_id`/`first_name`/`last_name`/`email_address` round-tripped, echo probe confirms camelCase, audit log | 8/8 ✅ |
| 2 | GET pass-through (no body) | GET 200 | 1/1 ✅ |
| 3 | Spec loading verification | `e2e-rename.yaml` loaded, `e2e-header-inject.yaml` loaded | 2/2 ✅ |
| 4 | Shadow JAR verification | Java 17 class version, < 5 MB | 2/2 ✅ |
| 5 | SPI registration | Service file contains FQCN | 1/1 ✅ |
| 6 | PingAccess health | Started without errors | 1/1 ✅ |
| | **Plugin discovery** | Type, class, mode (Site-only) | 3/3 ✅ |
| | **Total** | | **18/18** |

### Scenarios Validated End-to-End

| Scenario | Coverage |
|----------|----------|
| S-002-01 | Request body transform (via bidirectional round-trip) |
| S-002-02 | Response body transform (via bidirectional round-trip) |
| S-002-03 | Bidirectional transform (full round-trip proof) |
| S-002-07 | Empty body (GET pass-through) |
| S-002-10 | No matching spec (GET to non-spec path) |
| S-002-17 | Plugin configuration (rule created via Admin API) |
| S-002-19 | SPI registration (service file in shadow JAR) |
| S-002-24 | Shadow JAR correctness (size, class version, contents) |

---

## Run History

| Date | Result | PA Version | Script Commit | Notes |
|------|--------|------------|---------------|-------|
| 2026-02-14 ~12:24 | 13/13 ✅ | 9.0.1.0 | `0947e4b` | Initial script creation. Captured in commit message. |
| 2026-02-14 14:58 | 18/18 ✅ | 9.0.1.0 | `093c688` | Strengthened assertions (bidirectional spec, field-level payload verification, direct echo probe). |

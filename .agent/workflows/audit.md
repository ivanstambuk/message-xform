---
description: Audit feature documentation for gaps, inconsistencies, type drift, cross-reference errors, and cross-feature ownership violations
---

# /audit — Feature Documentation Audit

## Invocation

```
/audit <feature-id> [scope...] [--deep]
```

**Examples:**
- `/audit 002 all` — full audit of spec, plan, tasks, scenarios
- `/audit 002 spec plan` — audit spec and plan only
- `/audit 004 tasks` — audit tasks only
- `/audit 002 spec` — audit spec (includes scenarios automatically)
- `/audit 002 all --deep` — deep audit with build verification and behavioral checks

**Parameters:**
- `<feature-id>` — Feature number, e.g. `002`, `004` (maps to `docs/architecture/features/<id>/`)
- `[scope...]` — One or more of: `spec`, `plan`, `tasks`, `scenarios`, `all`
  - `all` = spec + plan + tasks + scenarios
  - `spec` automatically includes `scenarios` (they are tightly coupled)
  - If omitted, defaults to `all`
- `--deep` — Enables Phases 6–8 (build verification, behavioral analysis, risk review).
  Without `--deep`, the audit covers Phases 0–5 only (type drift, cross-references,
  task status, terminology, structural completeness). Use `--deep` when preparing
  for implementation or after significant spec/codebase changes.

---

## Audit Process

### Phase 0 — Load Context (always do this first)

// turbo
1. Read the feature directory listing:
   ```
   ls docs/architecture/features/<id>/
   ```

2. Read **all** documents in scope (view full file contents):
   - `spec.md` (if scope includes `spec`)
   - `scenarios.md` (if scope includes `spec` or `scenarios`)
   - `plan.md` (if scope includes `plan`)
   - `tasks.md` (if scope includes `tasks`)

3. Identify the **core SPI types** referenced by the feature. For each type
   mentioned in the spec (e.g., `Message`, `GatewayAdapter`, `TransformResult`,
   `TransformContext`, `MessageBody`, `HttpHeaders`, `SessionContext`):
   - Find the source file: `find_by_name <TypeName>.java`
   - Read the file outline or full file to capture the **current** field names,
     method signatures, and constructor parameters
   - This is the **ground truth** for type drift detection
   - **Trust signatures, not comments:** Javadoc/comments may be stale (e.g.,
     `GatewayAdapter.java` comments may still reference old `JsonNode` semantics).
     Only record/interface signatures, field declarations, and method return types
     count as ground truth. Comments are noise for drift detection.

4. Check implementation status:
   - `find_by_name *.java` in the feature's adapter/module `src/` directory
   - `git log --oneline -- docs/architecture/features/<id> adapter-* core` for
     path-scoped recent commits
   - `git log --oneline --all --grep='T-<id>-'` for task-ID-scoped commits
   - Compare actual source files against task checkboxes

5. If a reference adapter exists (e.g., `StandaloneAdapter` for adapter features),
   read its outline to understand established patterns. The reference adapter is
   the **implementation exemplar** — the new adapter should follow the same
   structural patterns unless the spec explicitly deviates.

---

### Phase 1 — Type & API Drift (spec vs codebase)

For each core type referenced in the spec, compare the spec's description
against the actual source code. Check for:

| Check | What to look for | Severity |
|-------|-----------------|----------|
| **Field names** | Spec says `sessionContext`, code says `session` | 🔴 Critical |
| **Field types** | Spec says `JsonNode`, code uses `MessageBody` | 🔴 Critical |
| **Field count** | Spec says "9 fields", record has 7 | 🟡 Medium |
| **Removed fields** | Spec references `headersAll` as separate field, but it's now part of `HttpHeaders` | 🔴 Critical |
| **Constructor params** | Spec shows constructor with params that no longer exist | 🟡 Medium |
| **Method signatures** | Spec references `objectMapper.readTree()` but adapter should use `MessageBody.json()` | 🟡 Medium |
| **Factory methods** | Spec says `new HashMap<>()` but code uses `HttpHeaders.of()` | 🟢 Low |
| **Enum values** | Spec lists enum values that don't match code | 🔴 Critical |

**How to detect:** Grep the spec for type names (`JsonNode`, `NullNode`, `ObjectNode`,
`Map<String, String>`, `Map<String, List<String>>`, etc.) and verify each usage
against the actual source. Pay special attention to:
- Mapping tables (FR-002-01 style: "Message Field | Source")
- Code snippets and pseudocode blocks
- Aspect tables (success/failure path descriptions)

**Accessor name drift:** Type-token greps catch `JsonNode` → `MessageBody`, but not
renamed accessors like `sessionContext()` → `session()`. Explicitly grep for
`Message.<accessor>` patterns in all docs and compare against the actual record's
field names. Check both the accessor name AND the field name in mapping tables.

**Javadoc staleness check (reverse direction — code → code):** For each core SPI
type loaded in Phase 0, scan the Javadoc comments for references to stale types
or patterns from the drift checklist. The signatures are correct (that's the ground
truth), but Javadoc may lag behind. For example, `GatewayAdapter.java`'s Javadoc
might still say "returns a `JsonNode` body" even though the method signature says
`MessageBody`. Flag stale Javadoc as 🟢 Low (it doesn't affect runtime behavior,
but misleads future developers and AI agents). This check is bounded to the
types already loaded — not a full codebase Javadoc audit.

---

### Phase 2 — Cross-Document Consistency

#### 2a. Spec ↔ Plan alignment (if both in scope)

| Check | What to look for | Severity |
|-------|-----------------|----------|
| **FR coverage** | Every FR in spec has a corresponding increment in plan | 🔴 Critical |
| **Increment descriptions** | Plan increment text matches spec FR descriptions | 🟡 Medium |
| **Type references** | Plan uses same types as spec (both should match code) | 🟡 Medium |
| **Scope alignment** | Plan doesn't add/omit scope vs spec | 🔴 Critical |

#### 2b. Spec ↔ Scenarios alignment (if both in scope)

| Check | What to look for | Severity |
|-------|-----------------|----------|
| **Scenario coverage** | Every FR that changes observable behavior has at least one scenario (per AGENTS.md Rule 9) | 🔴 Critical for behavior-changing FRs; 🟡 Medium for internal-only FRs |
| **Scenario accuracy** | Given/When/Then text matches spec behavior | 🟡 Medium |
| **Type references** | Scenarios use correct type names | 🟢 Low |
| **Edge case coverage** | Spec edge cases (null, empty, failure) have scenarios | 🟡 Medium |

#### 2c. Plan ↔ Tasks alignment (if both in scope)

| Check | What to look for | Severity |
|-------|-----------------|----------|
| **Task-to-increment mapping** | Every plan increment has corresponding tasks | 🔴 Critical |
| **Verification commands** | Task verification commands match plan commands | 🟢 Low |
| **Requirements covered** | Task `_Requirements covered_` matches plan's list | 🟡 Medium |
| **Type references** | Tasks use correct type names | 🟡 Medium |

#### 2d. Scenarios ↔ Plan traceability (if both in scope)

| Check | What to look for | Severity |
|-------|-----------------|----------|
| **Scenario-to-increment** | Plan increments reference the correct scenario IDs | 🟡 Medium |
| **Missing scenarios** | Plan references scenarios that don't exist | 🔴 Critical |

#### 2e. Backwards traceability (scope creep detection)

| Check | What to look for | Severity |
|-------|-----------------|----------|
| **Orphan tasks** | Tasks that don't trace back to any FR or plan increment | 🟡 Medium |
| **Orphan scenarios** | Scenarios without a corresponding FR | 🟢 Low |
| **Plan scope overflow** | Plan increment describes work not required by any FR | 🔴 Critical |
| **Non-goal violation** | Plan/tasks include items explicitly listed in spec's non-goals | 🔴 Critical |

**How to detect:** For each task/scenario, trace backwards to an FR. If no FR
justifies the task, it's either scope creep (finding) or a missing FR (different
finding). Similarly, check if plan increments introduce scope beyond the spec.

#### 2f. Cross-Feature Ownership Boundaries (Principle 8)

Every domain type, module, and code artefact is owned by exactly one feature.
This check detects violations of Principle 8 (Feature Ownership Boundaries)
where a feature's spec, plan, or tasks prescribe changes to types/code owned
by another feature.

| Check | What to look for | Severity |
|-------|-----------------|----------|
| **Inline core API change** | Plan step says "add field X to `TransformResult`" or "extend `Message` with Y" — but those types are owned by Feature 001 (DO-001-xx) | 🔴 Critical |
| **Cross-module task** | Task says "implement Z in `core/src/`" when the task belongs to Feature 002's task list | 🔴 Critical |
| **Spec claims foreign type** | Spec says "the `Foo` record is extended with..." where `Foo` is defined by another feature's spec | 🔴 Critical |
| **Missing prerequisite gate** | Plan increment depends on a core type change but doesn't declare it as a prerequisite from the owning feature | 🟡 Medium |
| **Foreign method reference** | Spec/scenario references a method (e.g., `TransformContext.cookiesAsJson()`) that doesn't exist on the type and no owning-feature task plans to add it | 🟡 Medium |

**How to detect:**
1. For each plan step, grep for keywords: "add field", "extend", "modify",
   "change", "thread through", "wire into" combined with core type names.
2. For each task, check that the code path it modifies is within the feature's
   own module (e.g., Feature 002 tasks should only modify `adapter-pingaccess/`).
3. For spec Method/API references (e.g., `TransformResult.specId()`), verify
   the method exists in the actual source code. If it doesn't, check whether
   the owning feature has a task to add it. If neither, flag as missing.
4. Check that plan increments with core dependencies explicitly declare
   prerequisite gates (e.g., "_Preconditions:_ **T-001-67 complete**").

**Ownership lookup:** The owning feature is the one whose spec introduced the
type's design object ID (e.g., `TransformResult` = DO-001-05 → Feature 001).
When in doubt, check which feature's `spec.md` contains the type's `DO-xxx-yy`
definition.

---

### Phase 3 — Task Status Accuracy (if tasks in scope)

For each task marked `[x]` (complete):

1. **Verify source files exist:** Check the module's `src/` directory for the
   files the task claims to have created.
2. **Verify git commits:** Search `git log --oneline --all` for commits
   referencing the task ID or related filenames.
3. **Verify tests pass:** If verification commands are listed, note whether
   they can be validated.

Flag as 🔴 Critical if a task is marked complete but:
- No source files exist
- No git commits reference it
- The implementation was in a session that never committed

**Pre-commit checklist compliance** (per AGENTS.md Rule 6): For each completed
task, verify the task commit includes the `_Verification log:_` with test count,
result, and commit hash. Flag non-compliant completions as 🟡 Medium.

**Rule 19 compliance — no silent rewrites of completed tasks:** Check `git log -p`
for completed tasks to see if their description, intent, or verification criteria
were modified *after* completion. Per Rule 19 (Spec Amendment Cascade), new work
must be **additive** — new task IDs that implement the delta, never in-place edits
of completed tasks. Flag as 🟡 Medium if a completed task's content was changed.

*Exception:* Unchecking a **falsely completed** task (marked `[x]` but no code/commits
exist) is a legitimate correction, not a Rule 19 violation. The task was never
truly completed, so the audit trail it claims to preserve doesn't exist.

---

### Phase 4 — Terminology & Convention Compliance

| Check | What to look for | Severity |
|-------|-----------------|----------|
| **Terminology alignment** | Terms match `docs/architecture/terminology.md` | 🟢 Low |
| **ADR references** | Referenced ADRs exist and are current (not superseded) | 🟡 Medium |
| **Status headers** | Document status headers are accurate | 🟡 Medium |
| **Status drift gate** | If spec says "Spec Ready" but tasks show implementation edits or amended requirements, flag the mismatch. The feature lifecycle status must be consistent across all docs. | 🔴 Critical |
| **NFR compliance notes** | NFR violations are documented (e.g., reflection) | 🟡 Medium |
| **Commit message format** | Task commits follow `<type>(<scope>): T-xxx-yy — <desc>` | 🟢 Low |

---

### Phase 5 — Structural & Completeness Checks

| Check | What to look for | Severity |
|-------|-----------------|----------|
| **Orphaned references** | Spec references files/classes that don't exist yet (expected) vs ones that should exist (bug) | 🟡 Medium |
| **Stale line-number references** | Plan references "spec lines 271-274" but lines shifted after edits | 🟢 Low |
| **Missing test strategy** | Spec defines test classes but plan/tasks don't cover them | 🟡 Medium |
| **Constraint coverage** | All spec constraints are testable/tested | 🟡 Medium |
| **Non-goals drift** | Plan/tasks accidentally include items listed as non-goals | 🔴 Critical |
| **Quantitative self-consistency** | Scenario count in matrix matches actual scenarios; FR count matches; task count per phase matches plan | 🟡 Medium |

**Quantitative checks to perform:**
// turbo
- Count `S-NNN-XX` IDs in scenarios.md → compare to scenario matrix row count
- Count `FR-NNN-XX` IDs in spec.md → compare to FR index/summary
- Count `T-NNN-XX` IDs in tasks.md → compare task count per phase header
- Count `NFR-NNN-XX` IDs → compare to NFR section count
- Verify ID sequences are **unique with no duplicates** (letter-suffix gaps like
  `T-002-10` → `T-002-10a` → `T-002-11` are acceptable per the project's
  suffix convention; numeric gaps like `S-002-15` → `S-002-17` should be flagged)

---

### Phase 6 — Behavioral Correctness (--deep only)

> **This phase goes beyond syntactic matching to verify the described behavior
> is actually correct and implementable.**

| Check | What to look for | Severity |
|-------|-----------------|----------|
| **Algorithm soundness** | Described algorithms (e.g., header diff strategy) produce correct results for all edge cases | 🔴 Critical |
| **MUST/SHOULD/MAY conflicts** | Two FRs say contradictory things (one says MUST, another implies SHOULD NOT) | 🔴 Critical |
| **Implicit assumptions** | Spec assumes something without stating it (e.g., "single-threaded" without saying so) | 🟡 Medium |
| **Ambiguous requirements** | FR text that could be interpreted two different ways | 🟡 Medium |
| **Missing error paths** | Happy path described but failure mode not specified | 🟡 Medium |
| **Ordering dependencies** | Steps described in wrong order (e.g., "apply headers then read body" but body read needs Content-Type) | 🔴 Critical |

**How to detect:** For each FR, mentally walk through the described behavior
step by step. Ask:
- "What happens if this input is null?"
- "What happens if this step fails?"
- "Is there a race condition between these steps?"
- "Does this assumption hold in all deployment scenarios?"

For mapping tables, verify that the described source API actually provides what
the spec claims. Cross-reference against SDK documentation or decompiled sources.

---

### Phase 7 — Build & CI Verifiability (--deep only)

// turbo
| Check | What to look for | Severity |
|-------|-----------------|----------|
| **Module exists** | `build.gradle.kts` or equivalent exists for the feature's module | 🔴 Critical |
| **Dependencies declared** | All SDK/library dependencies referenced in spec are in the build file | 🟡 Medium |
| **Verification commands work** | Task verification commands actually compile/run | 🟡 Medium |
| **Shadow JAR config** | If spec describes packaging, verify shadow plugin config matches | 🟡 Medium |
| **Version catalog alignment** | Dependency versions in build file match spec's dependency table | 🟢 Low |

**How to verify (non-mutating only — audit must not change the repo):**
```bash
# Check module compiles (writes only to gitignored build/ — safe)
./gradlew :<module>:compileJava

# Check tests compile (even if they fail — tests for unimplemented code are expected)
./gradlew :<module>:compileTestJava 2>&1 | tail -5

# Verify code style without reformatting (spotlessCheck, NOT spotlessApply)
./gradlew :<module>:spotlessCheck 2>&1 | tail -5

# Check dependency resolution
./gradlew :<module>:dependencies --configuration compileClasspath | head -30
```

---

### Phase 8 — Risk & Constraint Freshness (--deep only)

| Check | What to look for | Severity |
|-------|-----------------|----------|
| **Constraint accuracy** | Each spec constraint is still accurate given current codebase state | 🟡 Medium |
| **Risk relevance** | Plan risks are still valid (not mitigated by completed work) | 🟢 Low |
| **Backlog freshness** | Backlog items haven't been accidentally implemented or made obsolete | 🟢 Low |
| **Dependency freshness** | Inter-feature dependencies (e.g., F002 depends on F001 types) reflect current state | 🟡 Medium |
| **SDK/API version accuracy** | Version numbers in spec match actual dependencies | 🟡 Medium |

---

## Output Format

After completing all phases, produce a **Findings Report** in this format:

```markdown
# Feature <ID> Documentation Audit — Findings

**Scope:** spec, plan, tasks, scenarios
**Mode:** standard | deep
**Date:** <current date>
**Documents audited:** <list of files>
**Core types checked:** <list of types verified against source>

## Summary

| Severity | Count |
|----------|-------|
| 🔴 Critical | X |
| 🟡 Medium | X |
| 🟢 Low | X |
| ✅ Clean phases | <list> |

## Findings

### 🔴 Critical

#### F-001: <short title>
- **Phase:** <which phase found this>
- **Location:** `spec.md:273` (FR-002-XX)
- **Anchor:** `grep -n '<searchable text snippet>' spec.md`
- **Issue:** <description>
- **Evidence:** `<exact symbol, signature, or brief excerpt that proves the finding>`
- **Expected:** <what it should say>
- **Actual:** <what it currently says>
- **Fix:** <concrete fix description>
- **Confidence:** HIGH | MEDIUM (see severity calibration)

### 🟡 Medium
...

### 🟢 Low
...

## Open Questions (if any)

Items that need user judgment before they can be classified as findings or
dismissed. These should be discussed before applying any fixes.

1. <question needing user input>

## Recommendations

1. <prioritized list of actions>
2. ...
```

---

## Severity Calibration Guide

Use these criteria to assign severity consistently:

| Severity | Criteria | Examples |
|----------|----------|---------|
| 🔴 **Critical** | Would cause an implementation error if coded as-documented. Wrong types, wrong field names, missing behavior, contradictory requirements. | Spec says `JsonNode`, code is `MessageBody`. Task marked done but no code exists. |
| 🟡 **Medium** | Inconsistency that could cause confusion or wasted effort but a competent developer would likely catch during implementation. | Field count says 9 instead of 7. Stale method reference. Missing edge-case scenario. |
| 🟢 **Low** | Cosmetic, style, or minor documentation hygiene issue. No risk of implementation error. | Stale line-number cross-reference. Terminology preference. Factory method naming. |

**Confidence levels:**
- **HIGH** — The finding is objectively verifiable (e.g., comparing code to spec text).
- **MEDIUM** — The finding requires interpretation or the fix may have multiple options.
  Include the interpretation in the finding description.

---

## Post-Audit Actions

After presenting the findings report:
1. **Wait for user approval** before making any changes
2. If the user says "fix all" or "fix issues 1-4", apply the fixes
3. Each fix should be a targeted edit (not a full rewrite)
4. After fixes, re-run the relevant checks to verify no regressions
5. Do NOT start implementation — this is a documentation-only workflow
6. **Open questions** from the findings report should be discussed with the
   user and resolved via the Decision Card protocol (AGENTS.md Rule 9) if
   they represent design decisions. If they're spec clarifications, update
   the spec directly after user confirmation.

---

## Integration with Other Workflows

| Workflow | How /audit connects |
|----------|-------------------|
| `/init` | Run `/audit` after `/init` when a feature is about to enter implementation. `/init` assesses project state; `/audit` deep-dives a specific feature. |
| `/retro` | The `/retro` step 3e ("Spec Consistency Check") is a lightweight version of `/audit`. When `/retro` finds issues, suggest running a full `/audit`. |
| Implementation | Run `/audit <id> all` before starting the first task of a feature to catch drift early. Run `/audit <id> tasks` periodically during implementation to verify task status accuracy. |

---

## Checklist of Common Drift Patterns

These are the most frequently observed drift patterns to watch for.
Check these explicitly during Phase 1:

- [ ] `JsonNode` → `MessageBody` (body port type refactoring)
- [ ] `NullNode` → `MessageBody.empty()` (empty body representation)
- [ ] `ObjectNode` → `SessionContext` / `Map<String, Object>` (session port type)
- [ ] `Map<String, String>` headers → `HttpHeaders` (header port type)
- [ ] `Map<String, List<String>>` headersAll → `HttpHeaders.toMultiValueMap()` (merged into HttpHeaders)
- [ ] `contentType` as Message field → derived from `MessageBody.mediaType()` (removed field)
- [ ] `headersAll` as Message field → `HttpHeaders` provides both views (removed field)
- [ ] `sessionContext` field name → `session` (renamed)
- [ ] "9 Message fields" → "7 Message fields" (field count after port type refactoring)
- [ ] `objectMapper.writeValueAsBytes(body)` → `MessageBody.content()` (direct byte access)
- [ ] `objectMapper.readTree()` for body parsing → validation-only parse + `MessageBody.json(bytes)` factory
- [ ] Task `[x]` status → verify source files and git commits actually exist
- [ ] Body parsing contract mismatch: spec says adapter swallows parse failures
      but core engine throws on invalid JSON → verify which layer handles errors
- [ ] Accessor rename without type change: `sessionContext()` → `session()` (same
      `SessionContext` type but different accessor name — grep won't catch via type token)
- [ ] **Principle 8 violation — inline core API change:** Plan step says "add field
      X to `TransformResult`" or "extend `Message`" inside a non-owning feature's plan.
      Must be refactored to a task in the owning feature (Principle 8).
- [ ] **Principle 8 violation — phantom method reference:** Spec/scenario references
      a method (e.g., `TransformContext.cookiesAsJson()`) that doesn't exist on the
      type and no owning-feature task creates it.
- [ ] **Principle 8 violation — missing prerequisite gate:** Plan increment depends
      on a core type change but doesn't declare a prerequisite from the owning feature.

> **Note:** This checklist should be updated as new drift patterns are discovered.
> When you find a new pattern during an audit, append it to this list.

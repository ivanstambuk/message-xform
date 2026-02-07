# Open Questions – Decision Card Format

Status: Draft | Last updated: 2025-12-14

This document defines the standard “Decision Card” format for all medium‑ and high‑impact open questions that are presented to humans (for example in chat, design docs, or reviews).

> Scope: This format is for **presentation** of a single open question (for example in chat or in a spec section). The tracking table in `docs/4-architecture/open-questions.md` remains the lightweight index of questions/options; it should reference options in the same A/B/C order and preferred option as the Decision Card.

## 1. Decision Card Template

When formatting an open question, use the following structure verbatim, adapting only IDs, titles, and content. Keep the emoji and heading levels as shown.

```markdown
### ❓ Q-XXX · Short descriptive title

**Status:** Open  
**Feature:** F-XXX – Short feature name  
**Preferred option:** 🅰️ (**recommended**) Option A – Option title  

**Context**  
High/medium-level context that makes the decision self-contained. Include enough detail that the human can decide without follow-up questions.

- **Current behaviour / contract today:** What the repo currently specifies or implies (cite file paths/sections).
- **Why this is a decision (what’s ambiguous):** The conflict, gap, or choice that must be settled.
- **Decision scope:** What this decision covers (and, optionally, what it explicitly does not cover).
- **Stakeholders / impact:** Who/what is affected (security, UX, compatibility, ops).
- **Key references:** Bullet list of the most relevant authoritative docs (DSL sections, ADRs, feature specs, OpenAPI).
- **Example (optional but recommended):** A tiny snippet or scenario illustrating the issue (avoid long code unless necessary).

**Question**  
Short, human-readable question text (one or a few sentences).

---

#### 🅰️ (**recommended**) Option A – Option title
- **Idea:** Short description of what this option proposes.
- **Spec impact:** How this option changes or constrains the spec.
- **Pros:**  
  - ✅ Bullet point 1  
  - ✅ Bullet point 2  
  - ✅ Bullet point 3 (optional)
- **Cons:**  
  - ❌ Bullet point 1  
  - ❌ Bullet point 2  
  - ❌ Bullet point 3 (optional)

---

#### 🅱️ Option B – Option title
- **Idea:** Short description of what this option proposes.
- **Spec impact:** How this option changes or constrains the spec.
- **Pros:**  
  - ✅ Bullet point 1  
  - ✅ Bullet point 2  
- **Cons:**  
  - ❌ Bullet point 1  
  - ❌ Bullet point 2  

---

#### 🅲 Option C – Option title
- **Idea:** Short description of what this option proposes.
- **Spec impact:** How this option changes or constrains the spec.
- **Pros:**  
  - ✅ Bullet point 1  
  - ✅ Bullet point 2  
- **Cons:**  
  - ❌ Bullet point 1  
  - ❌ Bullet point 2  

---

**Next action**  
Who needs to decide what, and where/when (for example:  
“IAM WG to choose between 🅰️ and 🅱️ in WG-003 on 2025-12-10; update ADR-00XX accordingly.”)
```

### 1.1 Rules

- Always mark exactly one option as preferred in the metadata line and in its section heading, using `(**recommended**)` **immediately after the emoji**, before the option title.
- Options must be listed in **preference order** (A is most preferred, then B, then C, etc.), consistent with `docs/4-architecture/open-questions.md`.
- If there are more or fewer options than A/B/C, extend or shrink the list while keeping the same pattern (🅰️, 🅱️, 🅲, 🅳, …).
- The Decision Card MUST include the **Context** section and it MUST be sufficient for an informed decision without follow-ups.
- Do **not** add extra meta sections (no TL;DR, summary, criticism, etc.) beyond what is defined in the template.
- **Workspace-first grounding:** the “Current behaviour / contract today” and “Key references” bullets MUST be based on the repository’s current authoritative documents (DSL reference, ADRs, feature specs, OpenAPI) and MUST cite concrete file paths/sections. Do not rely on chat memory as the primary source for these facts.

## 2. Relationship to `open-questions.md`

- The table in `docs/4-architecture/open-questions.md` remains the single scratchpad for tracking open questions, their IDs, and their options in compressed form.
- When presenting a question to a human (for example in chat or in a spec/ADR discussion), render it as a Decision Card using this format.
- The “Options (A preferred)” column in `open-questions.md` must:
  - List options in the same order and with the same labels (A/B/C/…) as the Decision Card.
  - Match the preferred option from the Decision Card (Option A is the recommended path unless a different option is explicitly marked as preferred).

# Open Questions – Decision Card Format

Status: Active | Last updated: 2026-02-07

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
The context section uses **progressive disclosure** — move from plain-language overview to technical detail. The reader should understand the *what* and *why* before encountering any technical vocabulary. Structure the context using the following sub-bullets **in order**:

- **Plain-language problem statement:** In 2–4 sentences, explain the problem in terms a product owner could understand. No spec IDs, no config key names, no code. Answer: "What is this feature/concept? Why does it exist? What goes wrong if we don't decide?"
- **Define before use:** If the decision involves domain-specific terms, modes, or config options that the reader may not have in working memory, define them here with a one-line explanation and a concrete example *before* they appear in the options below. Do NOT assume the reader remembers every spec concept — even the spec author may not recall details introduced weeks ago.
- **Current behaviour / contract today:** What the repo currently specifies or implies (cite file paths/sections). This is where technical references belong — after the reader has context.
- **Why this is a decision (what's ambiguous):** The conflict, gap, or choice that must be settled.
- **Decision scope:** What this decision covers (and, optionally, what it explicitly does not cover).
- **Stakeholders / impact:** Who/what is affected (security, UX, compatibility, ops).
- **Key references:** Bullet list of the most relevant authoritative docs (DSL sections, ADRs, feature specs, OpenAPI).
- **Example (optional):** A tiny snippet or scenario illustrating the *problem* (avoid long code unless necessary).

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
- **Concrete example:**
  A short code/YAML/config snippet showing exactly what the system would look
  like if this option is adopted. Must be specific enough that the reader can
  visualise the user-facing artefact (spec YAML, API call, config file, etc.).
  ```yaml
  # Example YAML or code showing this option in practice
  ```

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
- **Concrete example:**
  ```yaml
  # Example YAML or code showing this option in practice
  ```

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
- **Concrete example:**
  ```yaml
  # Example YAML or code showing this option in practice
  ```

---

**Comparison Matrix**

A table comparing all options across the most decision-relevant dimensions
(e.g., complexity, portability, performance, alignment with existing ADRs).
Choose 3–6 dimensions that highlight the trade-offs.

| Aspect | 🅰️ Option A | 🅱️ Option B | 🅲 Option C |
|--------|-------------|-------------|-------------|
| Dimension 1 | ... | ... | ... |
| Dimension 2 | ... | ... | ... |
| Dimension 3 | ... | ... | ... |

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
- **Progressive disclosure (mandatory):** The Context section MUST follow the sub-bullet order defined in the template. Start with a plain-language problem statement, then define terms, *then* cite spec references and technical detail. The reader should never encounter a technical term (e.g., "strict mode", "lenient mode", "pipeline chaining") that hasn't been introduced in plain language first. If a reader needs to ask "what is this concept?" after reading the Context, the card has failed.
- **Define before use (mandatory):** If the decision introduces or questions a domain-specific concept (a config option, a mode, a strategy), the Context MUST include a brief definition (1–2 sentences) explaining what it is, why it was introduced, and what it does in practice — *before* the options reference it. Do NOT assume context from previous conversations.
- Do **not** add extra meta sections (no TL;DR, summary, criticism, etc.) beyond what is defined in the template.
- **Workspace-first grounding:** the “Current behaviour / contract today” and “Key references” bullets MUST be based on the repository’s current authoritative documents (DSL reference, ADRs, feature specs, OpenAPI) and MUST cite concrete file paths/sections. Do not rely on chat memory as the primary source for these facts.
- **Concrete examples are mandatory:** Every option MUST include a `Concrete example` block showing what the system would look like (spec YAML, API, config) if that option is adopted. The reader must be able to visualise the impact without follow-up questions.
- **Comparison Matrix is mandatory:** After all options, include a comparison table with 3–6 decision-relevant dimensions. This is the reader’s primary decision aid — make it count.

## 2. Relationship to `open-questions.md`

- The table in `docs/4-architecture/open-questions.md` remains the single scratchpad for tracking open questions, their IDs, and their options in compressed form.
- When presenting a question to a human (for example in chat or in a spec/ADR discussion), render it as a Decision Card using this format.
- The “Options (A preferred)” column in `open-questions.md` must:
  - List options in the same order and with the same labels (A/B/C/…) as the Decision Card.
  - Match the preferred option from the Decision Card (Option A is the recommended path unless a different option is explicitly marked as preferred).

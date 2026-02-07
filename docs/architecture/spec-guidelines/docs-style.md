# Docs Style Guide

Status: Draft | Last updated: 2026-02-07

Use this guide when writing or updating Markdown docs in `docs/`. It complements
the open questions format in `spec-guidelines/open-questions-format.md` and the
terminology in `docs/architecture/terminology.md`.

## 1. Document metadata

- Start every doc with:
  - `# <Title>`
  - `Status: Draft | Last updated: YYYY-MM-DD` (or `Accepted` / `Deprecated`).
- Keep `Last updated` in sync when making meaningful content changes.

## 2. Headings & structure

- Prefer clear, descriptive headings; avoid very deep nesting.
- For reference/spec-like docs that need stable citations, use numbered sections
  (`1.`, `2.1`, `2.2`, …) and keep those numbers stable over time.
- Avoid lettered sub-sections like `2a`, `2b`; use decimal numbering instead.
- Only introduce a manual table of contents when the doc is long and
  reference-heavy; otherwise rely on the renderer's outline.

## 3. Cross-references & citations

When referring to other normative docs, use a short, consistent citation style:

- General pattern: `<Alias> §<section>`, optionally linked.
- Aliases:
  - `Feature NNN` → `docs/architecture/features/NNN/spec.md`
  - `ADR-NNNN` → `docs/decisions/ADR-NNNN-*.md`
  - `Constitution` → `docs/decisions/project-constitution.md`
- First mention in a doc: include both alias and file path.
- Subsequent mentions: just use the alias and section.
- Avoid vague phrases like "see below" when a section number is available.

## 4. Links, paths & code

- Use repository-relative paths in Markdown links (no absolute filesystem paths).
  - Good: `[Feature 001 spec](docs/architecture/features/001/spec.md)`
  - Bad: `/home/ivan/dev/message-xform/docs/...`
- Wrap file paths, identifiers, and code in backticks:
  - `docs/architecture/roadmap.md`, `TransformSpec`, `NFR-001-08`.
- For inline commands, use backticks; for multi-line examples, use fenced code
  blocks with a language tag (`bash`, `yaml`, `json`, `java`, etc.).

## 5. Terminology & wording

- Follow `docs/architecture/terminology.md` for model terms (transform spec,
  transform profile, engine, expression engine, etc.).
- When introducing a term not in the terminology doc, either:
  - Add it there as part of the same change, or
  - Mark it as provisional and add an open question.
- Avoid inventing synonyms for core concepts (see the "Avoid" column in the
  canonical term map).
- Use active, precise language in normative sections ("The engine MUST …",
  "The profile configures …"). Reserve softer language ("may", "can") for
  non-normative guidance.

## 6. Spec requirements format

- Use RFC 2119 keywords: MUST, MUST NOT, SHOULD, SHOULD NOT, MAY.
- Functional requirements: `FR-NNN-XX` (e.g., `FR-001-05`).
- Non-functional requirements: `NFR-NNN-XX` (e.g., `NFR-001-08`).
- Each requirement in a spec table MUST have: ID, Requirement text, Driver,
  Measurement, Dependencies, Source.

## 7. ADR format

- Follow `docs/templates/adr-template.md`.
- Include: Context (with related ADRs), Options Considered (with rejected options
  and pros/cons), Decision, Consequences (Positive / Negative / Follow-ups).
- Reference validating scenarios by ID.

## 8. Status & evolution

- When a style rule needs to change non-trivially, capture the rationale in an ADR
  and update this file to match.
- Older docs that use slightly different styles do not need mass-editing, but
  SHOULD be normalised when making other substantial edits.

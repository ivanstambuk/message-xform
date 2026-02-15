# core — Known Pitfalls

Library quirks and gotchas discovered during development of the core engine.
Read this before working on anything in the `core` module.

## JSLT 0.1.14 Function Availability
The project uses JSLT `0.1.14` (see `gradle/libs.versions.toml`). Not all JSLT
built-in functions listed in the latest documentation are available in this version.
Notably:
- `uppercase()` is NOT available — calling it silently returns `null` (no compile
  error, no runtime error). This is a JSLT quirk: unknown function names at
  evaluation time produce `null` rather than throwing.
- `upper-case()` (hyphenated) throws `JsltException: No such function` at compile
  time, which is the correct behavior for a missing function.
- **Workaround:** Use string concatenation (`+`) or structural transforms in tests
  rather than relying on version-specific JSLT built-in string functions.

## JSLT `contains()` Argument Order
JSLT's `contains(element, sequence)` takes the **element first, then the
sequence** — the opposite of most `contains()` APIs (e.g., Java's
`collection.contains(element)`). Writing `contains($session.roles, "admin")`
silently evaluates to `false` because it checks whether the string `"admin"`
contains the array `$session.roles` (which is nonsensical). The correct form
is `contains("admin", $session.roles)`.

## JSLT Context Variables and Null-Safety
- Context variables are injected as external variables on each evaluation call.
  Referencing an undeclared variable (for example, `$querParams` typo) throws
  `JsltException` at evaluation time.
- `contains()` throws `JsltException` when its sequence argument is null or
  missing. Guard before calling it (for example, check field existence first).
- Excluding object fields requires JSLT matcher syntax:
  `{ * - secret : . }`. Pattern like `{ * : . } - "secret"` is invalid in JSLT.

## TransformRegistry.specCount() Returns 2× Unique Specs
`TransformRegistry.specCount()` returns the internal map size, which stores each
spec under **both** its `id` key and its `id@version` key. This means 1 unique
spec file = 2 map entries = `specCount() == 2`. For user-facing reporting (e.g.,
admin reload JSON response), use the number of spec files scanned
(`specPaths.size()`) instead of `engine.specCount()`.

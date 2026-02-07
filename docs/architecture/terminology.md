# message-xform – Terminology

Status: Draft | Last updated: 2026-02-07

This document defines common terms used across the message-xform docs and specs so we
can use consistent vocabulary. It is the golden source for terminology — any new
terminology agreements must be captured here immediately.

## Core concepts

- **Transform spec** (`TransformSpec`)
  - A self-contained YAML file that defines a single transformation: what input looks
    like, what the output should be, and the expression(s) to get there.
  - Contains: `id`, `version`, `input.schema`, `output.schema`, `transform` block
    (JSLT expression), optional `headers` block, optional `status` block, optional
    `reverse` block.
  - A transform spec is **portable** — it has no knowledge of gateways, URL paths, or
    deployment context. It is a pure function: `input JSON → output JSON`.
  - Multiple versions of the same spec MAY be loaded concurrently (ADR-0005).

- **Transform profile** (`TransformProfile`)
  - A deployment-specific YAML file that binds transform specs to URL patterns.
  - Contains: `profile` id, `transforms` list with `spec` references (using
    `id@version` syntax), `match` criteria (path glob, method, content-type), and
    `direction` (request/response).
  - Profiles are **not portable** — they are specific to a deployment context.
  - When multiple profiles match the same request, most-specific-wins resolution
    applies (ADR-0006).

- **Engine** (core engine)
  - The message-xform execution component: loads transform specs, compiles
    expressions, matches profiles to requests, and evaluates transforms.
  - Preferred wording: "the engine" or "the transform engine". Avoid introducing
    "runtime" as a generic noun.

- **Expression engine** (pluggable)
  - A pluggable evaluation component selected via `lang: <engineId>` in a transform
    spec's `transform` block.
  - Implemented via the Expression Engine SPI (FR-001-02). Candidate engines: `jslt`
    (baseline), `jolt`, `jq`, `jsonata`, `dataweave`.
  - Expression engines are pure — they compute values and return results. They do
    not perform external I/O or control flow.
  - Not all engines support the same capabilities. The engine support matrix
    (ADR-0004) documents per-engine capabilities.

- **Engine id**
  - The string identifier used in `lang` to select an expression engine (e.g.,
    `jslt`, `jolt`, `jq`, `jsonata`, `dataweave`).
  - Resolved by the engine's Expression Engine registry; unknown ids MUST be rejected
    at load time.

## Message envelope

- **Message** (`Message`)
  - The domain object representing a gateway request or response. Contains three
    dimensions: body (JSON), headers (multi-valued map), and status code (integer).
  - The engine operates on all three dimensions — body via JSLT expressions, headers
    via the declarative `headers` block, status via the declarative `status` block.

- **Body**
  - The JSON payload of the message. Represented as Jackson `JsonNode` internally.
  - Transform expressions (JSLT) operate on the body.

- **Headers**
  - HTTP headers as a `Map<String, List<String>>`. Manipulated via the declarative
    `headers` block (add/remove/rename) and the read-only `$headers` JSLT variable.
  - `$headers` exposes the **first value** of each header (v1).

- **Status code**
  - The HTTP response status code (integer). Manipulated via the declarative `status`
    block and exposed as the read-only `$status` JSLT variable.

## Versioning & resolution

- **Spec version**
  - Semver string on a transform spec (e.g., `1.0.0`). Profiles reference specs
    by `id@version` for explicit pinning (ADR-0005).

- **Version pinning**
  - The practice of referencing a specific spec version in a profile using `id@version`
    syntax. Enables concurrent versions and gradual migration.

- **Latest resolution**
  - When a profile references a spec **without** `@version`, the engine resolves to
    the latest loaded version (highest semver). Production profiles SHOULD always use
    explicit version pinning.

- **Specificity score**
  - The count of literal (non-wildcard) path segments in a profile's match pattern.
    Used for most-specific-wins resolution when multiple profiles match (ADR-0006).
    Higher score = more specific = wins.

## Transform lifecycle

- **Load time** (spec load / profile load)
  - When specs and profiles are loaded from YAML into compiled engine objects.
    All validation occurs here: schema syntax, engine capability checks, version
    resolution, ambiguous match detection.
  - Prefer "load time" over "deploy time" or "startup time".

- **Evaluation time**
  - When a request arrives and the engine evaluates the matched transform spec
    against the message body.
  - Runtime schema validation (strict/lenient mode) occurs here.

- **Passthrough**
  - When a message does not match any transform profile, or the body is not valid
    JSON, the engine passes it through **completely unmodified** — no body, header,
    or status code changes.

## Bidirectional transforms

- **Forward transform**
  - The primary transform direction: `input → output` using the `transform` block.

- **Reverse transform**
  - The inverse direction: `output → input` using the `reverse` block. Enables
    bidirectional specs (FR-001-03) where the same spec handles both request and
    response transformation.

- **Direction**
  - `request` or `response` — indicates whether the transform applies to the
    incoming request or the outgoing response. Specified in the profile.

## Observability

- **TelemetryListener** (SPI interface)
  - A plain Java interface in the core module that receives semantic transform
    events (started, completed, failed, matched, loaded, rejected). Adapter
    modules provide concrete OTel/Micrometer bindings. See ADR-0007.

- **Structured log entry**
  - A JSON-formatted log line emitted by the engine for operational events. Every
    matched profile produces a structured log entry (NFR-001-08) containing:
    profile id, spec id@version, request path, specificity score, evaluation duration.

- **Trace context**
  - Incoming correlation headers (`X-Request-ID`, `traceparent`) propagated through
    all log entries and telemetry events (NFR-001-10). The engine participates in the
    caller's trace — it does not create new traces.

## Wording conventions

- Use **"transform spec"** for the transformation definition YAML.
- Use **"transform profile"** for the deployment binding YAML.
- Use **"the engine"** for the core execution component.
- Use **"expression engine"** for the pluggable evaluation component (JSLT, JOLT, etc.).
- Use **"load time"** for spec/profile loading and validation.
- Use **"evaluation time"** for runtime transform execution.

### Canonical term map (use vs avoid)

| Use | Avoid |
|-----|-------|
| transform spec | mapping, transformation rule, transform definition |
| transform profile | route config, binding config, deployment spec |
| the engine | the runtime, the transformer, the processor |
| expression engine | language plugin, eval plugin, script engine |
| load time | deploy time, startup time, init time |
| evaluation time | runtime, execution time (when referring to transform execution) |
| body | payload (except in JourneyForge context), message body |
| passthrough | bypass, skip, no-op |
| specificity score | priority number, weight, rank |

### Process for improving terminology

- When a better term or clearer definition is identified, propose it via
  `docs/architecture/open-questions.md` or an ADR and update this document.
- Before merging new docs/specs, search (`rg`) for avoided terms to ensure they
  do not appear in normative text.
- This document is the golden source. Subsequent docs must follow it without exception.

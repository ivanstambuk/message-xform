# PingAccess Adapter — Deployment Architecture

> How the message-xform plugin integrates with PingAccess at the deployment level.

| Field | Value |
|-------|-------|
| Status | Draft |
| Last updated | 2026-02-12 |
| Audience | Architects, operators, developers |
| Related spec | [`spec.md`](../architecture/features/002/spec.md) |
| Related ADRs | [ADR-0023](../decisions/ADR-0023-cross-profile-routing-is-product-defined.md), [ADR-0006](../decisions/ADR-0006-profile-match-resolution.md) |

---

## Overview

The message-xform PingAccess adapter is a **Rule plugin** deployed into PingAccess.
PingAccess administrators bind rule instances to Applications (API endpoints) through
the PA admin console. The plugin itself does not control *which* requests it processes —
PingAccess decides that. The plugin only controls *what* happens to the request/response
once PA hands it the `Exchange`.

This creates a clean **two-level matching** architecture:

```
Level 1: PingAccess routing          Level 2: Engine profile matching
─────────────────────────────        ──────────────────────────────────
PA decides WHICH rule fires    →     Engine decides WHICH spec applies
(Application → Site → Rules)         (profile path/method/content-type)
```

---

## Two-Level Matching

### Level 1 — PingAccess Routing (Product-Defined)

PingAccess owns request routing. The admin binds rule instances to Applications:

```
┌──────────────────────────────────────────────────────────────┐
│                    PingAccess Admin Console                   │
│                                                              │
│  Application: "Customer API"                                 │
│    Context root: /api/customers/*                            │
│    Site: backend-cluster-1                                   │
│    Rules:                                                    │
│      1. OAuth Token Validation          (built-in)           │
│      2. Message Transform — instance A  ◄── our plugin       │
│                                                              │
│  Application: "Payment API"                                  │
│    Context root: /api/payments/*                             │
│    Site: backend-cluster-2                                   │
│    Rules:                                                    │
│      1. OAuth Token Validation          (built-in)           │
│      2. Rate Limiting                   (built-in)           │
│      3. Message Transform — instance B  ◄── our plugin       │
│                                                              │
│  Application: "Health Check"                                 │
│    Context root: /health                                     │
│    Site: backend-cluster-1                                   │
│    Rules: (none)                        ◄── no transform     │
└──────────────────────────────────────────────────────────────┘
```

Key points:
- Each Application can have **zero or more** MessageTransform rule instances.
- Each rule instance is an **independent plugin instance** with its own configuration.
- PA fires the rules in order for every request matching the Application's context root.
- Our plugin has **no visibility** into which Application it belongs to — it receives
  an `Exchange` and processes it.

### Level 2 — Engine Profile Matching (message-xform)

Once PA fires our rule, the engine performs its own matching within the active
**transform profile**. The engine receives `requestPath`, `requestMethod`, and
`contentType` from the `Exchange` and matches against profile entries:

```
                    Exchange from PingAccess
                            │
                            ▼
               ┌────────────────────────┐
               │  MessageTransformRule  │
               │  (our plugin)          │
               └────────┬───────────────┘
                        │
           requestPath: /api/customers/123
           method:      PUT
           contentType: application/json
                        │
                        ▼
               ┌──────────────────────────────────────┐
               │  TransformEngine (profile matching)   │
               │                                       │
               │  Active profile entries:              │
               │    /api/customers/*  PUT  → spec-A    │  ◄── match! ✓
               │    /api/customers/*  GET  → spec-B    │
               │    /api/orders/*     *    → spec-C    │
               │                                       │
               │  Winner: spec-A (most-specific-wins)  │
               └──────────────────────────────────────┘
                        │
                        ▼
               ┌────────────────────────┐
               │  JSLT transform runs   │
               │  (spec-A applied)      │
               └────────────────────────┘
```

The engine uses **most-specific-wins** resolution (ADR-0006) when multiple entries
within a single profile match the same request. If no profile entry matches, the
request **passes through** untouched.

### The Two Levels Combined

```
  Client                PingAccess                    Plugin                  Backend
    │                       │                            │                       │
    │  PUT /api/customers/1 │                            │                       │
    │──────────────────────►│                            │                       │
    │                       │                            │                       │
    │               Level 1 │ (PA routing)               │                       │
    │               "Customer API" app matches           │                       │
    │               Rule chain: OAuth → MsgTransform-A   │                       │
    │                       │                            │                       │
    │                       │  handleRequest(exchange)   │                       │
    │                       │───────────────────────────►│                       │
    │                       │                            │                       │
    │                       │                    Level 2 │ (engine matching)     │
    │                       │                    Profile entry matches           │
    │                       │                    spec-A applied                  │
    │                       │                    Body transformed                │
    │                       │                            │                       │
    │                       │  ◄─ applyChanges()         │                       │
    │                       │                            │                       │
    │                       │  Forward transformed req   │                       │
    │                       │───────────────────────────────────────────────────►│
    │                       │                            │                       │
    │                       │  ◄──────────────────────── Backend response ──────│
    │                       │                            │                       │
    │                       │  handleResponse(exchange)  │                       │
    │                       │───────────────────────────►│                       │
    │                       │                    spec-A reverse applied          │
    │                       │  ◄─ applyChanges()         │                       │
    │                       │                            │                       │
    │  ◄──────────────────── Transformed response        │                       │
    │                       │                            │                       │
```

---

## Per-Instance Configuration

Each rule instance has its **own configuration**, set through the PA admin UI or
REST API. There is no "global" configuration shared between instances.

```
┌──────────────────────────────┐    ┌──────────────────────────────┐
│  Instance A                  │    │  Instance B                  │
│  "Customer API Transform"    │    │  "Payment API Transform"     │
│                              │    │                              │
│  specsDir:     /specs/cust   │    │  specsDir:     /specs/pay    │
│  profilesDir:  /profiles     │    │  profilesDir:  /profiles     │
│  activeProfile: customer-api │    │  activeProfile: payment-api  │
│  errorMode:    PASS_THROUGH  │    │  errorMode:    DENY          │
│  reloadInterval: 30s         │    │  reloadInterval: 0 (off)     │
│  schemaValidation: LENIENT   │    │  schemaValidation: STRICT    │
│                              │    │                              │
│  ┌────────────────────────┐  │    │  ┌────────────────────────┐  │
│  │ Own TransformEngine    │  │    │  │ Own TransformEngine    │  │
│  │ Own TransformRegistry  │  │    │  │ Own TransformRegistry  │  │
│  │ Own spec reload timer  │  │    │  │ (no reload — loaded    │  │
│  └────────────────────────┘  │    │  │  once at startup)      │  │
└──────────────────────────────┘    │  └────────────────────────┘  │
                                    └──────────────────────────────┘
```

Each instance initializes its own `TransformEngine` during the plugin lifecycle's
`configure()` step. Instances are **completely independent** — they don't share
state, specs, or profiles.

### Configuration Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `specsDir` | Path | `/specs` | Directory containing transform spec YAML files |
| `profilesDir` | Path | `/profiles` | Directory containing transform profile files |
| `activeProfile` | String | (empty) | Profile name to activate (empty = no profile) |
| `errorMode` | Enum | `PASS_THROUGH` | Behaviour on transform failure |
| `reloadIntervalSec` | Integer | `0` | Seconds between spec file re-reads (0 = disabled) |
| `schemaValidation` | Enum | `LENIENT` | JSON Schema validation mode |
| `enableJmxMetrics` | Boolean | `false` | Enable JMX MBean metrics |

---

## File System Layout

Transform specs and profiles live on the PingAccess server's filesystem. In Docker
deployments, these are typically mounted as volumes.

### Single-Instance Layout (Simple)

When all API operations share one set of specs:

```
/opt/server/
├── deploy/
│   └── message-xform-adapter.jar        ← plugin JAR (shadow JAR)
│
├── specs/                                ← specsDir (volume mount)
│   ├── customer-transform.yaml
│   ├── payment-transform.yaml
│   └── order-transform.yaml
│
└── profiles/                             ← profilesDir (volume mount)
    └── api-transforms.yaml              ← active profile
```

### Multi-Instance Layout (Per-Team / Per-API)

When different teams own different API operations and want isolated specs:

```
/opt/server/
├── deploy/
│   └── message-xform-adapter.jar        ← single JAR, shared by all instances
│
├── specs/
│   ├── customer/                         ← Instance A: specsDir=/specs/customer
│   │   ├── create-customer.yaml
│   │   └── update-customer.yaml
│   │
│   └── payment/                          ← Instance B: specsDir=/specs/payment
│       ├── process-payment.yaml
│       └── refund-payment.yaml
│
└── profiles/
    ├── customer-api.yaml                ← Instance A: activeProfile=customer-api
    └── payment-api.yaml                 ← Instance B: activeProfile=payment-api
```

### Docker Compose Example

```yaml
services:
  pingaccess:
    image: pingidentity/pingaccess:9.0.1
    volumes:
      # Plugin JAR
      - ./build/libs/message-xform-adapter.jar:/opt/server/deploy/message-xform-adapter.jar:ro

      # Transform specs (separate dirs per team)
      - ./transforms/customer/specs:/specs/customer:ro
      - ./transforms/payment/specs:/specs/payment:ro

      # Transform profiles
      - ./transforms/profiles:/profiles:ro
    ports:
      - "3000:3000"   # PA engine port
      - "9000:9000"   # PA admin port
```

### Kubernetes Example

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: customer-transforms
data:
  create-customer.yaml: |
    id: create-customer
    version: "1.0.0"
    transform:
      lang: jslt
      expr: |
        { "firstName": .first_name, "lastName": .last_name }
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: pingaccess
spec:
  template:
    spec:
      containers:
        - name: pingaccess
          image: pingidentity/pingaccess:9.0.1
          volumeMounts:
            - name: plugin-jar
              mountPath: /opt/server/deploy/message-xform-adapter.jar
              subPath: message-xform-adapter.jar
            - name: customer-specs
              mountPath: /specs/customer
      volumes:
        - name: plugin-jar
          configMap:
            name: plugin-artifact
        - name: customer-specs
          configMap:
            name: customer-transforms
```

---

## Spec Hot-Reload

When `reloadIntervalSec > 0`, the plugin periodically re-reads spec YAML files from
disk. This enables **zero-downtime spec updates** — operators can update the volume-
mounted files without restarting PingAccess.

```
Time ─────────────────────────────────────────────────────────►

  t=0        t=30s       t=60s       t=90s
   │           │           │           │
   ▼           ▼           ▼           ▼
  Load      Reload      Reload      Reload
  specs     (no change) (new spec!) (no change)
   │           │           │           │
   │           │           │           │
   ▼           ▼           ▼           ▼
  Registry   Same       New          Same
  v1         v1         Registry     v2
                        v2

  In-flight requests using v1          New requests use v2
  complete normally (AtomicReference   ──────────────────►
  holds the old reference until GC)
```

**Failure is safe:** If a reload fails (malformed YAML, I/O error), the previous
valid registry stays active. No requests are affected.

**Note:** Hot-reload applies to spec/profile **YAML files only** — not to the plugin
JAR itself. Updating the JAR requires a PA restart (Constraint #2 in spec).

---

## Deployment Patterns

### Pattern 1: Single Rule, One Profile (Simplest)

One MessageTransform rule instance, bound globally or to a single Application.
The profile handles all path matching internally.

```
┌────────────────────────────────────────────┐
│  PingAccess                                │
│                                            │
│  Global Rules:                             │
│    1. MessageTransform (instance A)        │
│       specsDir: /specs                     │
│       activeProfile: all-apis              │
│                                            │
│  Profile "all-apis":                       │
│    /api/customers/*  → customer-spec       │
│    /api/payments/*   → payment-spec        │
│    /api/orders/*     → order-spec          │
│    /*                → (no match = pass)   │
│                                            │
└────────────────────────────────────────────┘
```

**Pros:** Simple to manage. One place for all transforms.
**Cons:** All specs reload together. One misconfigured spec can block all others.

### Pattern 2: Per-Application Rules (Recommended)

Separate MessageTransform rule instances per Application. Each instance has its
own spec directory and profile.

```
┌────────────────────────────────────────────────────────────┐
│  PingAccess                                                │
│                                                            │
│  Application: "Customer API" (/api/customers/*)            │
│    Rules: MessageTransform-A                               │
│      specsDir: /specs/customer                             │
│      activeProfile: customer-api                           │
│      errorMode: PASS_THROUGH                               │
│                                                            │
│  Application: "Payment API" (/api/payments/*)              │
│    Rules: MessageTransform-B                               │
│      specsDir: /specs/payment                              │
│      activeProfile: payment-api                            │
│      errorMode: DENY                                       │
│                                                            │
│  Application: "Internal API" (/internal/*)                 │
│    Rules: (no MessageTransform)                            │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

**Pros:** Team isolation. Independent error modes. Targeted reload.
**Cons:** More rule instances to manage.

### Pattern 3: Shared Specs, Separate Profiles

All instances point to the same `specsDir` but use different `activeProfile` values
to select which specs apply.

```
┌────────────────────────────────────────────────────────────┐
│  Shared spec directory: /specs/                            │
│    ├── customer-transform.yaml                             │
│    ├── payment-transform.yaml                              │
│    └── order-transform.yaml                                │
│                                                            │
│  Profile "customer-api":                                   │
│    /api/customers/* → customer-transform                   │
│                                                            │
│  Profile "payment-api":                                    │
│    /api/payments/*  → payment-transform                    │
│                                                            │
│  Instance A: specsDir=/specs, activeProfile=customer-api   │
│  Instance B: specsDir=/specs, activeProfile=payment-api    │
│                                                            │
│  Both instances load ALL specs, but the profile filters    │
│  which specs actually apply to which requests.             │
└────────────────────────────────────────────────────────────┘
```

**Pros:** Single source of truth for specs. Profile controls routing.
**Cons:** Both instances load all specs (wasted memory for unused specs).

---

## FAQ

**Q: Can two rule instances process the same request?**
Yes, if the PA admin binds two MessageTransform rules to the same Application.
They execute independently in rule-chain order. This is by PA's design and is
not something the plugin controls (ADR-0023).

**Q: What if no profile entry matches the request?**
The engine returns `PASSTHROUGH` — the request/response goes through **completely
untouched**. No body, header, or status code changes.

**Q: Can I use the plugin without profiles?**
Yes. Set `activeProfile` to empty. The engine will attempt to match specs
directly by their `match` blocks (prerequisite filters). However, profiles are
recommended for explicit control.

**Q: Does the plugin see the original client URL or the rewritten URL?**
The **rewritten URL** — i.e., `Request.getUri()` after any upstream rule rewrites.
If a prior ParameterRule rewrites `/old` to `/new`, the plugin sees `/new`. This
is by design: profile matching should operate on the URL that will reach the backend.

**Q: How do I update specs without downtime?**
Set `reloadIntervalSec` to a value like `30`. Update the YAML files on disk (or
the ConfigMap in Kubernetes). The plugin picks up the changes at the next poll.
No PA restart needed.

**Q: What happens during a reload if a spec is invalid?**
The reload fails gracefully — the previous valid registry stays active. A warning
is logged. All existing transforms continue to work.

---

## See Also

- [Feature 002 Spec](../architecture/features/002/spec.md) — Full functional requirements
- [PingAccess SDK Guide](../reference/pingaccess-sdk-guide.md) — SDK class reference
- [ADR-0023](../decisions/ADR-0023-cross-profile-routing-is-product-defined.md) — Cross-profile routing is product-defined
- [ADR-0006](../decisions/ADR-0006-profile-match-resolution.md) — Most-specific-wins match resolution
- [ADR-0031](../decisions/ADR-0031-pa-provided-dependencies.md) — PA-provided dependencies

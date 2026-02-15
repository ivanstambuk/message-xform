# message-xform

**Payload & Header Transformation Engine for API Gateways**

Transform HTTP message payloads and headers declaratively — no code changes per API.
Deploy as a **standalone reverse proxy** or embed as a **plugin** in your existing API gateway.

<p align="center">
  <img src="docs/images/architecture-overview.png" alt="message-xform architecture — standalone proxy and gateway plugin deployment modes" width="100%">
</p>

---

## What is message-xform?

message-xform is a **gateway-agnostic transformation engine** that rewrites HTTP request and response messages based on declarative YAML configuration. It sits between your clients and backend APIs, transforming JSON payloads, manipulating headers, rewriting URLs, and mapping status codes — all without touching application code.

**The problem it solves:** API gateways often need to transform messages between different formats — legacy systems speaking one schema, modern APIs expecting another, headers that need to be promoted into payloads or vice versa. Instead of writing custom code for each gateway product, message-xform provides a **single transformation engine** that works across all of them.

---

## Two Deployment Modes

### 🔀 Standalone Reverse Proxy

Run message-xform as an **independent HTTP proxy** with zero external dependencies. Ideal for:

- **Development & testing** — validate transforms locally before deploying to a gateway
- **Kubernetes sidecar** — run alongside your backend in the same pod
- **Gateway-free environments** — when you don't need a full API gateway

**Key features:**
- Docker image (~100 MB) with multi-stage build
- Java 21 virtual threads for high-concurrency request handling
- TLS termination (inbound + outbound) with mTLS support
- Environment variable overrides for all configuration
- Health (`/health`) and readiness (`/ready`) endpoints
- Hot reload via file watcher + admin API (`POST /admin/reload`)
- Connection pooling with configurable timeouts

```bash
# Quick start with Docker
docker run -v ./specs:/specs -v ./config.yaml:/config.yaml \
  -p 8080:8080 message-xform-proxy

# Or run directly with Java 21
java -jar message-xform-proxy.jar
```

### 🔌 Gateway Plugin

Embed message-xform **directly into your existing API gateway** as a native plugin, rule, or filter. The core engine runs inside the gateway's JVM — no network hop, no sidecar overhead.

**Supported gateways:**

| Gateway | Integration Model | Status |
|---------|-------------------|--------|
| **Standalone Proxy** | Embedded HTTP proxy (Javalin/Jetty) | ✅ Complete |
| **PingAccess** | Java plugin via `AsyncRuleInterceptor` SPI | ✅ Complete |
| **PingGateway** | Java/Groovy filter chain | 🔲 Planned (Tier 2) |
| **WSO2 API Manager** | Java extension API | 🔲 Planned (Tier 3) |
| **Apache APISIX** | Java Plugin Runner | 🔲 Planned (Tier 3) |
| **Kong** | Sidecar proxy (Lua ecosystem) | 🔲 Planned (Tier 4) |
| **NGINX** | Sidecar proxy (njs/C ecosystem) | 🔲 Planned (Tier 4) |

> **Tier 1–3** gateways support **direct Java integration** — the core engine runs natively inside the gateway.
> **Tier 4** gateways use a **sidecar pattern** — the standalone proxy runs alongside the gateway, which proxies through it.

---

## What Can It Transform?

<p align="center">
  <img src="docs/images/transform-pipeline.png" alt="message-xform transformation pipeline — body, headers, status, URL rewriting" width="100%">
</p>

message-xform operates on **four dimensions** of an HTTP message:

### 📦 JSON Body Transformation

Restructure, rename, and reshape JSON payloads using [JSLT](https://github.com/schibsted/jslt) expressions — a powerful JSON query and transformation language.

```yaml
# Transform a legacy payload to a modern API format
transform:
  lang: jslt
  expr: |
    {
      "user": {
        "firstName": .legacy.first_name,
        "lastName":  .legacy.last_name,
        "email":     .contact.email
      }
    }
```

### 📋 Header Manipulation

Add, remove, or rename HTTP headers declaratively. Promote payload fields to headers or inject header values into payloads.

```yaml
headers:
  add:
    X-Correlation-ID: "generated-uuid"
  remove:
    - X-Internal-Debug
  rename:
    X-Old-Header: X-New-Header

  # Promote a JSON field to a header
  payload-to-headers:
    - source: "$.auth.token"
      header: "Authorization"
      prefix: "Bearer "
      strip: true   # Remove from payload after promotion
```

### 🔢 Status Code Mapping

Conditionally override HTTP response status codes:

```yaml
status:
  set: 201
  when: '$status == 200 and .created != null'
```

### 🔗 URL Rewriting

Rewrite request paths, query parameters, and HTTP methods — enabling **de-polymorphization** of dispatch-style endpoints:

```yaml
# Convert POST /dispatch?action=delete to DELETE /api/users/{id}
url:
  path:
    expr: '"/api/users/" + string(.resourceId)'
  method:
    set: DELETE
    when: '.action == "delete"'
```

### ↔️ Bidirectional Transforms

Define both request and response transformations in a single spec:

```yaml
transform:
  expr: '{ "name": .legacy_name }'    # Request: legacy → modern
reverse:
  expr: '{ "legacy_name": .name }'    # Response: modern → legacy
```

### 🔐 Session Context Access

Access identity and session data inside JSLT expressions via the `$session` variable — populated from gateway-provided identity attributes (OAuth claims, OIDC session state, SAML assertions):

```yaml
transform:
  lang: jslt
  expr: |
    {
      "user":    $session.sub,
      "email":   $session.email,
      "payload": .
    }
```

---

## Configuration

Transforms are defined in **YAML spec files** — one per transformation — and bound to URL patterns via **profiles**:

```yaml
# specs/user-transform.yaml — the transformation logic
id: user-transform
version: 1.0.0
input:
  schema: { type: object }
output:
  schema: { type: object }
transform:
  lang: jslt
  expr: '{ "user": { "name": .legacy.name } }'
headers:
  add:
    X-Transformed: "true"
```

```yaml
# profile.yaml — binds specs to routes
profile: my-api
transforms:
  - spec: user-transform@1.0.0
    match:
      path: /api/v1/users/**
      method: [POST, PUT]
      content-type: application/json
    direction: request
```

**Key design principles:**

- **Specs are portable** — pure transformation logic, no gateway knowledge
- **Profiles are deployment-specific** — bind specs to URL patterns per environment
- **Version pinning** — reference specs by `id@version` for safe concurrent upgrades
- **Hot reload** — update specs without restarting the proxy or gateway

---

## Quick Start

### Prerequisites

- **Java 21** (for building from source)
- **Docker** (for containerized deployment)

### Build from Source

```bash
git clone https://github.com/ivanstambuk/message-xform.git
cd message-xform

# Run the full quality gate (format + compile + test)
./gradlew --no-daemon spotlessApply check

# Build the standalone proxy shadow JAR
./gradlew --no-daemon :adapter-standalone:shadowJar

# Build the PingAccess adapter shadow JAR
./gradlew --no-daemon :adapter-pingaccess:shadowJar

# Build the Docker image (~100 MB)
docker build -t message-xform-proxy adapter-standalone/
```

### Run the Standalone Proxy

```bash
# With Docker
docker run \
  -v ./my-specs:/specs \
  -v ./my-profile.yaml:/profile.yaml \
  -e MXFORM_BACKEND_HOST=api.example.com \
  -e MXFORM_BACKEND_PORT=443 \
  -e MXFORM_BACKEND_TLS_ENABLED=true \
  -p 8080:8080 \
  message-xform-proxy

# Or directly with Java
java -jar adapter-standalone/build/libs/adapter-standalone-*-all.jar
```

### Deploy the PingAccess Plugin

```bash
# Build the shadow JAR
./gradlew :adapter-pingaccess:shadowJar

# Copy to PingAccess deploy directory
cp adapter-pingaccess/build/libs/adapter-pingaccess-*-all.jar \
   /opt/pingaccess/deploy/

# Restart PingAccess — the plugin auto-registers via ServiceLoader
```

---

## Project Structure

```
message-xform/
├── core/                    # Gateway-agnostic transformation engine
│   ├── model/               # TransformSpec, TransformProfile, Message
│   ├── engine/              # TransformEngine, TransformRegistry
│   ├── spi/                 # ExpressionEngine, TelemetryListener
│   └── schema/              # JSON Schema validation
├── adapter-standalone/      # Standalone HTTP reverse proxy
│   ├── adapter/             # GatewayAdapter SPI implementation
│   ├── proxy/               # ProxyHandler, UpstreamClient, FileWatcher
│   ├── config/              # YAML config loader with env var overlay
│   └── tls/                 # TLS/mTLS configuration
├── adapter-pingaccess/      # PingAccess 9.0 gateway plugin
│   ├── adapter/             # GatewayAdapter + RuleInterceptor SPI
│   ├── config/              # Plugin descriptor + SnakeYAML config
│   └── metrics/             # JMX MBean metrics (optional)
├── e2e-pingaccess/          # End-to-end Karate tests against live PA
│   ├── docker-compose.yml   # PA + echo backend + mock OAuth2
│   └── src/test/            # Karate feature files (31 scenarios)
├── docs/                    # Specifications, ADRs, research
│   ├── architecture/        # Feature specs, roadmap, terminology
│   ├── decisions/           # Architecture Decision Records (36 ADRs)
│   └── operations/          # Deployment and operations guides
└── Dockerfile               # Multi-stage build (~100 MB image)
```

## Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Java 21 (virtual threads) |
| Build | Gradle 9.2 (Kotlin DSL) |
| HTTP Server | Javalin 6 / Jetty 12 |
| JSON Processing | Jackson 2.17 |
| Transform Language | [JSLT](https://github.com/schibsted/jslt) (pluggable SPI) |
| Schema Validation | networknt json-schema-validator |
| Testing | JUnit 5, AssertJ, Karate |
| E2E Testing | [Karate](https://karatelabs.github.io/karate/) + Docker Compose |
| Formatting | Palantir Java Format (via Spotless) |
| CI | GitHub Actions |

---

## PingAccess Adapter

The PingAccess adapter embeds the transformation engine as a native **RuleInterceptor** plugin:

- **AsyncRuleInterceptor** SPI — non-blocking request + response interception (Site rules only)
- **Bidirectional transforms** — request and response in a single spec
- **Session context** — OAuth claims, OIDC attributes, and SAML assertions available as `$session` in JSLT
- **Profile routing** — multiple specs bound to URL patterns per PA Application/Resource
- **Hot reload** — file-based spec reloading without PA restart
- **JMX metrics** — active spec count, transform counts, error rates (opt-in via plugin config)
- **Shadow JAR** — single deployable JAR with relocated dependencies; uses PA-provided Jackson/SLF4J
- **Tested against PA 9.0.1** — 31 E2E scenarios validated via Karate + Docker Compose

---

## License

Licensed under the [Apache License 2.0](LICENSE).

---

<p align="center">
  <sub>Built with ☕ Java 21 · Declarative YAML · Zero-dependency core</sub>
</p>

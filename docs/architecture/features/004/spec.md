# Feature 004 – Standalone HTTP Proxy Mode

| Field | Value |
|-------|-------|
| Status | Not Started |
| Last updated | 2026-02-07 |
| Owners | Ivan |
| Roadmap entry | #4 – Standalone HTTP Proxy Mode |
| Depends on | Feature 001 (core engine) |

## Overview

The message-xform engine running as an independent **HTTP reverse proxy** without any
gateway. Receives HTTP requests, applies matching transform profiles, forwards to the
configured upstream, transforms the response, and returns to the client.

Useful for:
- **Development & testing** — test transforms without deploying to a gateway
- **Sidecar pattern** — run alongside NGINX/Kong as a transformation sidecar
- **Lightweight deployments** — when a full gateway isn't needed

## Scope (to be elaborated)

- HTTP server (Jetty or Netty embedded)
- Reverse proxy with configurable upstream
- Transform profile matching by URL/method/content-type
- YAML configuration for upstream, specs-dir, profiles-dir
- Docker image packaging
- Health check endpoint
- Metrics/observability

## Implementation Language

**Java 21** — same as core.

## Status

🔲 Spec not yet written.

# Feature 002 – PingAccess Adapter

| Field | Value |
|-------|-------|
| Status | Not Started |
| Last updated | 2026-02-07 |
| Owners | Ivan |
| Roadmap entry | #2 – PingAccess Adapter |
| Depends on | Feature 001 (core engine) |

## Overview

Gateway adapter that integrates message-xform with **PingAccess** via the Java Add-on
SDK. Implements the `RuleInterceptor` SPI to intercept request/response flows and apply
transformation specs.

## Key Research

- PingAccess Plugin API: `docs/research/pingaccess-plugin-api.md` (COMPLETE)
- PingAM Authentication API: `docs/research/pingam-authentication-api.md` (COMPLETE)

## Scope (to be elaborated)

- Implement `GatewayAdapter` SPI for PingAccess `Exchange` objects
- Wrap `Exchange.getRequest()` / `Exchange.getResponse()` as `Message`
- Plugin configuration UI (spec directory, error mode, reload interval)
- Plugin lifecycle (init, handle, destroy)
- Deployment packaging (JAR for PingAccess plugin directory)

## Implementation Language

**Java 21** — PingAccess SDK is Java-native. Direct core library dependency.

## Status

🔲 Spec not yet written. Research is complete.

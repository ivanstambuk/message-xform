# Gateway Adapter Candidates — Research Notes

> Researched: 2026-02-07
> Context: Evaluating which API gateways are realistic targets for message-xform adapters

---

## Evaluation Criteria

The core engine is **Java 21** (driven by the PingAccess ecosystem requirement). For every gateway adapter, the critical question is: *"Can this gateway call our Java code?"*

1. **Java interop** — Can the gateway call Java code natively? (critical for core reuse)
2. **Open source** — Is the gateway OSS or does it require a commercial license?
3. **Market relevance** — Is it widely deployed in enterprise environments?
4. **Plugin complexity** — How hard is it to write a request/response transformation plugin?

---

## Tier 1 — Java-Native (Can Reuse Core Directly)

These gateways can call Java code natively, meaning the `message-xform-core` library can be used directly without bridging or reimplementation.

### PingAccess (Primary Target)

- **Extension model:** Java Add-on SDK with SPI pattern (`RuleInterceptor`)
- **OSS:** No (commercial, Ping Identity)
- **Java interop:** Native — the SDK **is** Java
- **Assessment:** Primary target, already fully researched. The `RuleInterceptor` SPI with `handleRequest(Exchange)` / `handleResponse(Exchange)` and `setBodyContent(byte[])` is a perfect fit.
- **Research:** See `docs/research/pingaccess-plugin-api.md`

### PingGateway (formerly ForgeRock Identity Gateway / IG)

- **Extension model:** Java + Groovy filter/handler chain
- **OSS:** No (commercial, Ping Identity)
- **Java interop:** Native — same Java ecosystem as PingAccess
- **Assessment:** **Strong candidate.** Same vendor ecosystem as PingAccess and PingAM. Clients using PingAM likely also use PingGateway. The Groovy scripting model provides a lighter-weight option than compiled Java plugins. A shared Java core should serve both PingAccess and PingGateway adapters.
- **Research:** Not yet done (backlog item in `PLAN.md`)
- **Docs available:** `docs/reference/pinggateway-2025.11.txt` (37K lines)

### WSO2 API Manager

- **Extension model:** Java + Ballerina
- **OSS:** Yes (Apache 2.0)
- **Java interop:** Native — the entire platform is Java
- **Assessment:** **Strong candidate.** Fully Java-native, genuinely open source, and widely deployed in enterprise. The Java core would plug in directly. The Ballerina angle is interesting but not required — plain Java extensions work fine. Recommended as #2 adapter target after PingAccess/PingGateway.
- **Priority:** High

### Gravitee.io

- **Extension model:** Java plugins + Groovy scripts
- **OSS:** Yes (Apache 2.0)
- **Java interop:** Native — Java-based
- **Assessment:** Java-native and OSS, so technically easy. However, it's more **niche** — its strength is event-driven (Kafka, MQTT), not traditional API gateway use. Unless specific clients use Gravitee, deprioritize relative to WSO2.
- **Priority:** Medium-low

### Apache APISIX

- **Extension model:** Lua (native) + Java Plugin Runner (via Unix socket/RPC)
- **OSS:** Yes (Apache 2.0)
- **Java interop:** Via Java Plugin Runner — the Java core could run as-is, communicating with APISIX over a local socket
- **Assessment:** **Underrated gem.** The dedicated Java Plugin Runner means our Java core can be used without modification. The hot-reload capability is genuinely useful for transformation config changes. Worth investigating.
- **Priority:** Medium-high

### Google Apigee

- **Extension model:** JavaScript (Rhino/V8), Java callouts, Python (Jython)
- **OSS:** No (Google Cloud SaaS only)
- **Java interop:** Java callouts — works but with SaaS constraints
- **Assessment:** Supports Java callouts, which is technically perfect. But it's Google Cloud SaaS — you can't run it locally, and the vendor lock-in is real. Only worth supporting if there are specific enterprise clients on Apigee.
- **Priority:** Low (unless client-driven)

---

## Tier 2 — Possible with Bridging (Go/Lua, Wrapper Needed)

These gateways don't support Java natively. An adapter would require either bridging (JNI, sidecar process) or reimplementing the transformation logic in the gateway's native language.

### Kong Gateway

- **Extension model:** Lua (native via OpenResty), Go (sidecar process), WASM
- **OSS:** Yes (Apache 2.0)
- **Java interop:** None direct. Options:
  - **Lua reimplementation** — defeats the purpose of shared core
  - **Go sidecar + JNI/GraalVM** — fragile
  - **WASM** — Java→WASM compilation is immature
- **Assessment:** Kong is the **800-pound gorilla** of OSS gateways with massive market share. However, the language gap is real. It's most valuable as a **design reference** (study their transformer plugins for inspiration) rather than an early adapter target. The actual adapter would be complex.
- **Priority:** Medium (study first, adapter later)
- **Already in backlog:** Yes, with links to Kong's transformation plugins

### Tyk

- **Extension model:** Go (native), JavaScript (Otter), Python, Lua
- **OSS:** Yes (MPL 2.0)
- **Java interop:** None direct. The Python/JS middleware runs in embedded interpreters with limitations. No Java path.
- **Assessment:** Multi-language support sounds appealing, but in practice it's a Go shop. Similar challenges to Kong — would need either a Go wrapper or a sidecar approach.
- **Priority:** Low

### NGINX

- **Extension model:** njs (JavaScript), OpenResty (Lua), C modules
- **OSS:** Yes (BSD)
- **Java interop:** None. Four possible approaches:
  1. **njs (JavaScript)** — lightweight but limited API surface
  2. **OpenResty (Lua)** — mature, same ecosystem as Kong
  3. **C module** — max performance, highest dev cost
  4. **Standalone sidecar proxy** — run `message-xform-standalone` as HTTP proxy, NGINX proxies through it
- **Assessment:** Option 4 (sidecar) is most pragmatic — avoids the language gap entirely and reuses the Java core as-is. Decision deferred to implementation.
- **Priority:** Medium
- **Already in backlog:** Yes, with all 4 approaches documented

---

## Tier 3 — Poor Fit

These gateways are in the wrong ecosystem or have a philosophy mismatch with message-xform.

### Zuplo

- **Extension model:** TypeScript only (Cloudflare edge runtime)
- **OSS:** No (commercial SaaS)
- **Why poor fit:** TypeScript-only, commercial SaaS, no Java interop, no self-hosted option. Completely wrong ecosystem.

### KrakenD

- **Extension model:** Go plugins, CEL expressions
- **OSS:** Yes (Apache 2.0)
- **Why poor fit:** KrakenD **philosophically opposes** complex per-request scripting to maintain raw throughput. It discourages the kind of deep payload transformation that message-xform does. The anti-scripting philosophy is a fundamental mismatch.

### Traefik Proxy

- **Extension model:** Go via Yaegi interpreter
- **OSS:** Yes (MIT)
- **Why poor fit:** Go-only via the Yaegi interpreter. No Java path, no WASM, no sidecar pattern. Would require complete Go reimplementation.

### Gloo Edge (Envoy-based)

- **Extension model:** WASM (Rust, C++, AssemblyScript)
- **OSS:** Yes (Apache 2.0 for Envoy core)
- **Why poor fit:** WASM-based extensibility is cool in theory, but Java→WASM compilation (via GraalVM native-image or TeaVM) is still experimental and has serious limitations (no reflection, limited threading, restricted APIs). Not production-ready for a transformation engine in 2026.

---

## Recommended Adapter Priority

Based on the analysis above, the recommended order for adapter development:

| Priority | Gateway | Rationale |
|----------|---------|-----------|
| **1st** | PingAccess | Primary target, SDK fully researched, perfect fit |
| **2nd** | PingGateway | Same Ping vendor ecosystem, Java-native, natural complement |
| **3rd** | WSO2 | Java-native, truly open source (Apache 2.0), enterprise-grade |
| **4th** | Apache APISIX | Java Plugin Runner, OSS, high performance, hot-reload |
| **5th** | Kong | Design reference first, actual adapter harder (Lua/Go gap) |
| **—** | Standalone | Always available — the `message-xform-standalone` mode lets people use the engine without any gateway |

---

## Criticism of "Programmable Gateway" Pattern

The move toward extensible gateways has real trade-offs:

1. **"Logic Leak"** — Business logic in the gateway violates separation of concerns. If the gateway becomes "too smart," it becomes a monolith that is difficult to test, version, and debug.
2. **Performance** — Interpreted languages (Lua, Python, JS) in the request path add latency. WASM mitigates this but has sandbox overhead.
3. **Debugging** — Debugging custom scripts inside a high-throughput proxy is hard and often requires specialized talent.
4. **Vendor lock-in via extensions** — Custom extensions written in a proprietary framework (Apigee Java Callouts, Zuplo runtime) make migration nearly impossible without rewrite.

**message-xform's mitigation:** By keeping the core logic in a **gateway-agnostic Java library** with thin adapter wrappers, we minimize vendor lock-in and maximize testability. The core is unit-testable outside any gateway.

---

*Status: COMPLETE — All gateway candidates evaluated and documented*

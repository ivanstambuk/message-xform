# Image Assets

Generated infographics for the project README. Each image has its generation
prompt stored below so it can be regenerated or refined.

## standalone-proxy.png

**Generator:** Gemini (image generation)
**Created:** 2026-02-15
**Replaces:** Left half of `architecture-overview.png`

### Prompt

```
Technical infographic for an open-source project called "message-xform" — the STANDALONE REVERSE PROXY deployment mode. Clean, modern developer infographic on a white/light gray background with dark navy text and vibrant cyan, orange, and green accents.

A horizontal flow from left to right:

1. "API Client" box on the left (with a browser/app icon)
2. Arrow flowing into a large, prominently glowing "message-xform Proxy" box in the center. Inside this box, show the Core Engine as a contained component with these capability labels arranged neatly: "Payload Rewrite (JSLT)" · "Header Manipulation" · "URL Rewriting" · "Status Code Mapping" · "Session Context" · "JSON Schema Validation" · "Bidirectional Transforms" · "Hot Reload"
3. Arrow flowing out to a "Backend API" box on the right (with a server/database icon)

Below the proxy box, show two rows of deployment context:
- Row 1: Docker whale icon + Kubernetes helm icon + "Java 21" badge
- Row 2: Caption "Zero dependencies · TLS/mTLS · Virtual Threads"

At the top: Title "Standalone Reverse Proxy" with subtitle "Run as an independent HTTP proxy — no gateway required"

At the bottom: Feature badges in rounded pills: "YAML Config" · "Env Var Overrides" · "Health Endpoints" · "Connection Pooling" · "Admin API"

Do NOT render any layout metadata text like "left", "right", "center", "row 1", "row 2", "top", or "bottom" as visible labels in the image. These are placement instructions only.

Style: Clean flat vector illustration, subtle grid pattern background, no photorealism, high contrast dark text on light background, professional GitHub README quality. Wide aspect ratio 16:7.
```

---

## gateway-plugin.png

**Generator:** Gemini (image generation)
**Created:** 2026-02-15
**Replaces:** Right half of `architecture-overview.png`

### Prompt

```
Technical infographic for an open-source project called "message-xform" — the GATEWAY PLUGIN deployment mode. Clean, modern developer infographic on a white/light gray background with dark navy text and vibrant cyan, orange, and green accents.

A horizontal flow from left to right:

1. "API Client" box on the left (with a browser/app icon)
2. Arrow flowing into a large "API Gateway" box in the center. Inside this gateway box, show a smaller embedded "message-xform" component containing the Core Engine. The Core Engine shows capability labels: "Payload Rewrite (JSLT)" · "Header Manipulation" · "URL Rewriting" · "Status Code Mapping" · "Session Context" · "JSON Schema Validation" · "Bidirectional Transforms" · "Hot Reload". The gateway box surrounds the engine, showing that message-xform runs INSIDE the gateway's JVM — no network hop.
3. Arrow flowing out to a "Backend API" box on the right (with a server/database icon)

Below the gateway box, show supported gateways in a grid with status badges:
- "PingAccess ✅" (complete, highlighted in green)
- "PingGateway" (planned)
- "WSO2" (planned)
- "APISIX" (planned)
- "Kong" (sidecar)
- "NGINX" (sidecar)

At the top: Title "Gateway Plugin" with subtitle "Embed directly inside your API gateway — zero sidecar overhead"

At the bottom: Feature badges in rounded pills: "Native JVM Plugin" · "Shadow JAR Deploy" · "ServiceLoader Auto-Discovery" · "Session Context (OAuth/OIDC/SAML)" · "JMX Metrics"

Do NOT render any layout metadata text like "left", "right", "center", "top", or "bottom" as visible labels in the image. These are placement instructions only.

Style: Clean flat vector illustration, subtle grid pattern background, no photorealism, high contrast dark text on light background, professional GitHub README quality. Wide aspect ratio 16:7.
```

---

## transform-pipeline.png

**Generator:** Gemini (image generation)
**Created:** 2026-02-09

### Prompt

```
Technical infographic showing HTTP message transformation pipeline for "message-xform". White/light gray background with dark navy text and vibrant cyan, teal, and orange accents.

A horizontal flow from left to right:

An HTTP request box showing:
- "POST /api/legacy" as the request line
- JSON body snippet: { "legacy": { "first_name": "John" } }
- Headers: "X-Auth-Token: abc123"

Then an arrow flowing into a large glowing transformation engine box with six capability cards arranged in 2 rows of 3:
"Payload Rewrite" with JSON curly braces icon, "Header Ops" with list icon, "Payload↔Header" with swap arrows icon, "Status Code" with numbered badge icon, "URL Rewrite" with link chain icon, "Bidirectional" with two-way arrow icon

Then an arrow flowing out to a transformed HTTP response box showing:
- "PUT /api/v2/users" as the request line
- JSON body snippet: { "user": { "firstName": "John" } }
- Headers: "Authorization: Bearer abc123"

The arrows between the boxes should be stylized dotted or dashed cyan lines suggesting data flow.

A strip of feature badges at the bottom in rounded pills: "Declarative YAML" · "Pluggable Engines" · "Schema Validation" · "Zero Downtime Reload"

Do NOT render any layout metadata text like "left", "right", "center", "input", "output", "row 1", "row 2" as visible labels in the image. These are placement instructions only.

Style: Clean flat vector illustration, professional technical documentation quality, wide 16:7 aspect ratio, high contrast dark text on light background, subtle circuit board trace pattern in background.
```

---

## architecture-overview.png (DEPRECATED)

**Status:** Replaced by `standalone-proxy.png` + `gateway-plugin.png`
**Created:** 2026-02-09

Original combined image showing both deployment modes side by side.
Kept for reference until the new images are generated and placed.

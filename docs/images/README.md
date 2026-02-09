# Image Assets

Generated infographics for the project README. Each image has its generation
prompt stored below so it can be regenerated or refined.

## architecture-overview.png

**Generator:** Gemini (image generation)
**Created:** 2026-02-09

### Prompt

```
Technical infographic for an open-source project called "message-xform". Clean, modern developer infographic on a white/light gray background with dark navy text and vibrant cyan, orange, and green accents.

The diagram shows TWO DEPLOYMENT MODES side by side:

On the left, "STANDALONE PROXY MODE": A flow showing Client box → glowing "message-xform" proxy box (with transformation gear icons) → Backend API box. Below the proxy box: Docker whale icon and Kubernetes helm icon. Caption: "Zero dependencies · Docker · Kubernetes sidecar"

On the right, "GATEWAY PLUGIN MODE": A flow showing Client box → large "API Gateway" box that contains a smaller embedded "message-xform" box inside → Backend API box. Below, show gateway names stacked with status indicators: "PingAccess", "PingGateway", "WSO2", "APISIX", "Kong", "NGINX"

Between the two modes at bottom center: A rounded box labeled "Core Engine" with features listed: "JSLT Transforms · JSON Schema · Header Mapping · URL Rewriting · Hot Reload · RFC 9457"

At the top: Large bold title "message-xform" with tagline "Payload & Header Transformation Engine"

At the bottom: Feature badges in rounded pills: "Java 21" "YAML Config" "TLS/mTLS" "Bidirectional" "Version Pinning"

Do NOT render any layout metadata text like "left side", "right side", "center", "top", or "bottom" as visible labels in the image. These are placement instructions only.

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
"Body Transform" with JSON curly braces icon, "Header Ops" with list icon, "Payload↔Header" with swap arrows icon, "Status Code" with numbered badge icon, "URL Rewrite" with link chain icon, "Bidirectional" with two-way arrow icon

Then an arrow flowing out to a transformed HTTP response box showing:
- "PUT /api/v2/users" as the request line
- JSON body snippet: { "user": { "firstName": "John" } }
- Headers: "Authorization: Bearer abc123"

The arrows between the boxes should be stylized dotted or dashed cyan lines suggesting data flow.

A strip of feature badges at the bottom in rounded pills: "Declarative YAML" · "Pluggable Engines" · "Schema Validation" · "Zero Downtime Reload"

Do NOT render any layout metadata text like "left", "right", "center", "input", "output", "row 1", "row 2" as visible labels in the image. These are placement instructions only.

Style: Clean flat vector illustration, professional technical documentation quality, wide 16:7 aspect ratio, high contrast dark text on light background, subtle circuit board trace pattern in background.
```

# PingAccess Plugin API — Research Notes

> Source: *PingAccess 9.0 Official Documentation* (`docs/reference/pingaccess-9.0.pdf`)
> Extracted: 2026-02-07

---

## Overview

PingAccess provides the **Add-on SDK for Java**, enabling developers to extend PingAccess behavior through custom plugins. The SDK uses a **Service Provider Interface (SPI)** pattern with Java interfaces, base classes, dependency injection (Spring/`@Inject`), and a Maven build system.

**Maven repository:** `http://maven.pingidentity.com/release`
**Maven coordinates:** `com.pingidentity.pingaccess:pingaccess-sdk`

---

## Extension Points (SPIs)

The SDK provides 5 extension points:

| SPI | Interface | Purpose |
|-----|----------|---------|
| **RuleInterceptor** | `com.pingidentity.pa.sdk.policy.RuleInterceptor` | Custom authorization logic + request/response processing |
| **SiteAuthenticatorInterceptor** | `com.pingidentity.pa.sdk.siteauthenticator.SiteAuthenticatorInterceptor` | Custom site authentication (proxy→backend integration) |
| **IdentityMappingPlugin** | `com.pingidentity.pa.sdk.identitymapping.IdentityMappingPlugin` | Custom identity mapping for applications |
| **LoadBalancingPlugin** | `com.pingidentity.pa.sdk.ha.lb.LoadBalancingPlugin` | Custom load balancing strategies |
| **LocaleOverrideService** | `com.pingidentity.pa.sdk.localization.LocaleOverrideService` | Custom locale resolution for localization |

Each SPI has a corresponding **Async** variant for third-party service integration:
- `AsyncRuleInterceptor` / `AsyncRuleInterceptorBase`
- `AsyncSiteAuthenticatorInterceptor` / `AsyncSiteAuthenticatorInterceptorBase`
- `AsyncIdentityMappingPlugin` / `AsyncIdentityMappingPluginBase`
- `AsyncLoadBalancingPlugin` / `AsyncLoadBalancingPluginBase`

---

## RuleInterceptor — Primary Extension for message-xform

The `RuleInterceptor` is the most relevant SPI for message-xform. It provides:

### Key Methods

```java
// Process the inbound request BEFORE it's sent to PingAM
public Outcome handleRequest(Exchange exchange) throws AccessException {
    // Transform request body, headers, etc.
    return Outcome.CONTINUE; // or Outcome.DENY
}

// Process the outbound response BEFORE it's sent to the client
public void handleResponse(Exchange exchange) throws AccessException {
    // Transform response body, headers, etc.
}
```

### Exchange Object — The Core Data Model

The `Exchange` object represents a single request/response round-trip. It contains:

```
Exchange
├── getRequest() → Request
│   ├── getMethod() → Method
│   │   └── getMethodName() → String (GET, POST, etc.)
│   ├── getHeader() → Header
│   │   ├── add(key, value)
│   │   ├── getFirstValue(name)
│   │   ├── getValues(name) → List<String>
│   │   ├── getAllHeaderFields() → List<GroovyHeaderField>
│   │   ├── removeFields(name)
│   │   ├── getContentType() → MediaType
│   │   ├── setContentType(String)
│   │   ├── getContentLength() → int
│   │   ├── setContentLength(int)
│   │   ├── getCookies() → Map<String, String[]>
│   │   └── ... (Accept, Authorization, Host, etc.)
│   ├── getBody() → Body
│   │   ├── getContent() → byte[]
│   │   └── getLength() → int
│   ├── setBodyContent(byte[] content) ← REPLACES body + updates Content-Length
│   ├── getQueryStringParams() → Map<String, String[]>
│   ├── getPostParams() → Map<String, String[]>
│   └── uri / setUri(String)
│
├── getResponse() → Response (null until site responds)
│   ├── getStatusCode() → int
│   ├── setStatusCode(int)
│   ├── getStatusMessage() → String
│   ├── setStatusMessage(String)
│   ├── getHeader() → Header (same API as request headers)
│   ├── getBody() → Body
│   │   ├── getContent() → byte[]
│   │   └── getLength() → int
│   ├── setBodyContent(byte[] content) ← REPLACES body + updates Content-Length
│   └── isRedirect() → boolean
│
├── getIdentity() → Identity
│   ├── getSubject() → String
│   ├── getMappedSubject() → String
│   ├── getTrackingId() → String
│   ├── getTokenId() → String
│   ├── getTokenExpiration() → Date
│   └── getAttributes() → JsonNode
│
├── getRequestURI() → String
├── getRequestScheme() → String
├── getProperty(key) → Object
├── setProperty(key, value) ← store custom data across request/response
├── getSslData() → SslData
├── getTimeReqSent() → long
└── getTimeResReceived() → long
```

### Critical Finding: `setBodyContent(byte[])` 🎯

Both `Request` and `Response` objects expose `setBodyContent(byte[] content)` which:
- **Replaces the entire body** with new content
- **Automatically adjusts Content-Length** header

This is **exactly** what message-xform needs to:
1. Read PingAM's callback JSON response body
2. Transform it into a prettier format
3. Replace the response body before it reaches the client

Similarly for the request direction — intercepting the client's clean request, transforming it into PingAM's callback format, and replacing the request body.

---

## Plugin Development Workflow

### 1. Create Maven Project

```xml
<dependency>
    <groupId>com.pingidentity.pingaccess</groupId>
    <artifactId>pingaccess-sdk</artifactId>
    <version>9.0.0</version>
</dependency>
```

### 2. Implement the SPI

```java
package com.example.xform;

import com.pingidentity.pa.sdk.policy.RuleInterceptor;
import com.pingidentity.pa.sdk.policy.RuleInterceptorBase;
import com.pingidentity.pa.sdk.http.Exchange;
import com.pingidentity.pa.sdk.policy.Outcome;
import com.pingidentity.pa.sdk.exception.AccessException;

public class MessageTransformRule extends RuleInterceptorBase<MessageTransformConfig> {

    @Override
    public Outcome handleRequest(Exchange exchange) throws AccessException {
        // 1. Read client request body
        byte[] body = exchange.getRequest().getBody().getContent();
        
        // 2. Transform (e.g., clean JSON → PingAM callback JSON)
        byte[] transformed = transformRequest(body);
        
        // 3. Replace request body
        exchange.getRequest().setBodyContent(transformed);
        
        return Outcome.CONTINUE;
    }

    @Override
    public void handleResponse(Exchange exchange) throws AccessException {
        // 1. Read PingAM response body  
        byte[] body = exchange.getResponse().getBody().getContent();
        
        // 2. Transform (e.g., PingAM callback JSON → clean JSON)
        byte[] transformed = transformResponse(body);
        
        // 3. Replace response body
        exchange.getResponse().setBodyContent(transformed);
    }
}
```

### 3. Register the SPI

Create: `META-INF/services/com.pingidentity.pa.sdk.policy.RuleInterceptor`

Contents:
```
com.example.xform.MessageTransformRule
```

### 4. Build & Deploy

```bash
mvn install
cp target/message-xform-pingaccess-*.jar <PA_HOME>/deploy/
# RESTART PingAccess (required for custom plugins)
```

---

## Configuration Model

Plugins use a `PluginConfiguration` class with `@UIElement` annotations for the admin UI:

```java
private static class Configuration extends SimplePluginConfiguration {
    
    @UIElement(order = 10,
               type = ConfigurationType.TEXT,
               label = "Transform Config Path",
               required = true)
    @NotNull
    private String configPath;
    
    @UIElement(order = 20,
               type = ConfigurationType.SELECT,
               label = "Transform Direction",
               required = true)
    private String direction; // "request", "response", "both"
}
```

The JSON configuration maps directly to the `PluginConfiguration` fields.

---

## Lifecycle

1. Rule annotation interrogated → determines `PluginConfiguration` class
2. Spring autowiring + `@PostConstruct` initialization
3. `PluginConfiguration.setName(String)` called
4. JSON config mapped to `PluginConfiguration` fields
5. `ConfigurablePlugin.configure(PluginConfiguration)` called
6. `Validator.validate()` invoked
7. **Instance available for request processing** (`handleRequest` / `handleResponse`)

---

## Dependency Injection

Available for `@Inject`:
- `com.pingidentity.pa.sdk.util.TemplateRenderer`
- `com.pingidentity.pa.sdk.accessor.tps.ThirdPartyServiceModel` (for external HTTP calls)
- `com.pingidentity.pa.sdk.http.client.HttpClient` (async HTTP client)

Use `javax.inject.Inject` (not Spring annotations) for future-proofing.

---

## Rules for Agents vs. Sites

| Feature | Site Rules | Agent Rules |
|---------|-----------|-------------|
| `handleRequest` | ✅ | ✅ |
| `handleResponse` | ✅ | ❌ Not called |
| Request body | ✅ | ❌ Not available |
| Destination list | ✅ | ❌ Empty |

**Implication:** For message-xform to transform both request AND response bodies, the rule must target **Sites** (gateway deployment), not Agents.

Mark with: `@Rule(destination = RuleInterceptorSupportedDestination.Site)`

---

## Groovy Script Rules — Alternative to Java Plugins

PingAccess also supports **Groovy script rules** for simpler customizations, with access to the same Exchange object. This could be an alternative (or complementary) approach.

```groovy
// Transform response body (Groovy script rule)
def body = exc?.response?.body
if (body) {
    def json = new groovy.json.JsonSlurper().parse(body.getContent())
    // Transform the JSON structure
    def transformed = new groovy.json.JsonOutput().toJson(prettifiedJson)
    exc.response.setBodyContent(transformed.getBytes("UTF-8"))
}
pass()
```

---

## Important Constraints

1. **Restart required** — PingAccess must be restarted after deploying custom plugin JARs
2. **Response immutability** — Since PA 6.1, modifying request fields during response processing is **ignored** (logged as warning)
3. **Thread safety** — Plugin instances may be shared across threads
4. **No request body for Agent rules** — Only Site (gateway) deployment supports request body access
5. **Async for external calls** — Use `AsyncRuleInterceptor` + `HttpClient` for any outbound HTTP (e.g., calling PingAM directly)

---

## Implications for message-xform PingAccess Adapter

### Architecture

```
Client                 PingAccess                    PingAM
  │                        │                           │
  │  POST /auth/login      │                           │
  │  {"user":"x","pw":"y"} │                           │
  │─────────────────────►  │                           │
  │                        │ handleRequest():           │
  │                        │ Transform clean JSON →     │
  │                        │ PingAM callback format     │
  │                        │──────────────────────────► │
  │                        │                           │
  │                        │  ◄────────────────────────│
  │                        │ handleResponse():          │
  │                        │ Transform callback JSON →  │
  │                        │ clean JSON response        │
  │  {"token":"abc123"}    │                           │
  │  ◄─────────────────── │                           │
```

### What the Adapter Must Do

1. **Implement `RuleInterceptor`** (or `AsyncRuleInterceptor` if PingAM calls are needed)
2. **In `handleRequest()`:**
   - Read client request body via `exchange.getRequest().getBody().getContent()`
   - Parse client's clean JSON (e.g., `{"username": "bjensen", "password": "Ch4ng31t"}`)
   - Transform to PingAM callback format (with `authId`, `NameCallback`, `PasswordCallback`, etc.)
   - Replace body via `exchange.getRequest().setBodyContent(transformedBytes)`
   - Optionally manage `X-OpenAM-Username` / `X-OpenAM-Password` headers
3. **In `handleResponse()`:**
   - Read PingAM response body via `exchange.getResponse().getBody().getContent()`
   - Parse PingAM's callback JSON (with `authId`, `callbacks[]`, `tokenId`, etc.)
   - Transform to client's clean JSON format
   - Replace body via `exchange.getResponse().setBodyContent(transformedBytes)`
   - Handle `tokenId` → session cookie promotion if needed
4. **Configuration** via `PluginConfiguration` specifying:
   - Transform config path or inline YAML/JSON config
   - Which transformations to apply for which paths

### Key Design Decision

The transformation logic (JSON parsing, field mapping, restructuring) lives in **`message-xform-core`** (gateway-agnostic Java library). The PingAccess adapter is a thin wrapper that:
- Implements `RuleInterceptor`
- Extracts `byte[]` body and headers from the `Exchange`
- Delegates to `message-xform-core` for the actual transformation
- Writes the transformed result back to the `Exchange`

---

*Status: COMPLETE — PingAccess Add-on SDK fully documented from official source*
